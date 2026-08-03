package com.lucsartech.squish.pipeline;

import com.lucsartech.squish.compression.CompressionResult;
import com.lucsartech.squish.pipeline.CompressionPipeline.Handoff;
import com.lucsartech.squish.pipeline.CompressionPipeline.PipelineAbortedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Regression tests for the dead-consumer deadlock.
 *
 * <p>A writer that died (fatal SQLException, unchecked exception, Error) used to leave the
 * bounded result queue undrained: it filled up, every worker blocked forever in {@code put()},
 * the producer blocked behind them, and the run neither finished nor failed. These tests exercise
 * {@link Handoff}, the hand-off the pipeline stages now use, directly - a real
 * {@link CompressionPipeline} cannot be constructed here because its constructor opens a HikariCP
 * pool against Oracle.
 *
 * <p>Every test that could hang is wrapped in {@code assertTimeoutPreemptively}, so a regression
 * shows up as a failing test instead of a hanging suite.
 */
class PipelineHandoffTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long POLL_MILLIS = 25;

    /** Producer that puts items on a hand-off in a background thread and remembers how it ended. */
    private static final class Producer {
        final Thread thread;
        final AtomicInteger put = new AtomicInteger();
        final AtomicReference<Throwable> thrown = new AtomicReference<>();

        Producer(Handoff<String> handoff, int items) {
            this.thread = new Thread(() -> {
                try {
                    for (int i = 0; i < items; i++) {
                        handoff.put("item-" + i);
                        put.incrementAndGet();
                    }
                } catch (Throwable t) {
                    thrown.set(t);
                }
            }, "test-producer");
            thread.setDaemon(true);
            thread.start();
        }

        void joinWithin(Duration d) throws InterruptedException {
            thread.join(d.toMillis());
        }
    }

    @Nested
    @DisplayName("a dead consuming stage cannot block a producer forever")
    class DeadConsumer {

        @Test
        @DisplayName("a producer blocked on a full queue aborts once the only consumer dies")
        void blockedProducerAbortsWhenConsumerDies() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                // Capacity 1, one consumer, and that consumer never takes anything.
                var handoff = new Handoff<String>("writers", 1, 1, POLL_MILLIS);

                var producer = new Producer(handoff, 100);

                // The producer fills the queue and then blocks: back-pressure still works.
                Thread.sleep(200);
                assertThat(producer.thread.isAlive()).isTrue();
                assertThat(producer.thrown.get()).isNull();
                assertThat(producer.put.get()).isEqualTo(1);

                // The writer dies on a fatal SQLException.
                var fatal = new SQLException("ORA-03113: end-of-file on communication channel");
                handoff.consumerFailed(fatal);

                // Before the fix this join would time out - the producer was blocked in put() forever.
                producer.joinWithin(Duration.ofSeconds(5));
                assertThat(producer.thread.isAlive()).isFalse();
                assertThat(producer.thrown.get())
                        .isInstanceOf(PipelineAbortedException.class)
                        .hasCause(fatal);
                assertThat(handoff.fatalFailure()).isSameAs(fatal);
            });
        }

        @Test
        @DisplayName("a producer aborts when every consumer has gone, even without a failure")
        void producerAbortsWhenNoConsumerIsLeft() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                var handoff = new Handoff<String>("writers", 1, 2, POLL_MILLIS);

                var producer = new Producer(handoff, 100);
                Thread.sleep(200);
                assertThat(producer.thread.isAlive()).isTrue();

                handoff.consumerFinished();
                handoff.consumerFinished();

                producer.joinWithin(Duration.ofSeconds(5));
                assertThat(producer.thread.isAlive()).isFalse();
                assertThat(producer.thrown.get())
                        .isInstanceOf(PipelineAbortedException.class)
                        .hasMessageContaining("no live consumer");
            });
        }

        @Test
        @DisplayName("only the first failure is kept as the fatal cause")
        void firstFailureWins() {
            var handoff = new Handoff<String>("writers", 4, 2, POLL_MILLIS);
            var first = new SQLException("first");
            var second = new IllegalStateException("second");

            handoff.consumerFailed(first);
            handoff.consumerFailed(second);

            assertThat(handoff.fatalFailure()).isSameAs(first);
            assertThat(handoff.liveConsumers()).isZero();
        }

        @Test
        @DisplayName("shutdown sentinels are dropped rather than blocking when no consumer is left")
        void sentinelDoesNotBlockWhenConsumersAreDead() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                // Full queue, no live consumer: run() must still be able to 'signal' the writers.
                var handoff = new Handoff<String>("writers", 1, 1, POLL_MILLIS);
                handoff.put("fills-the-queue");
                handoff.consumerFailed(new SQLException("boom"));

                handoff.putSentinel("sentinel"); // must return instead of blocking forever
            });
        }

        @Test
        @DisplayName("aborting discards whatever is still queued")
        void discardPendingEmptiesTheQueue() throws InterruptedException {
            var handoff = new Handoff<String>("writers", 4, 1, POLL_MILLIS);
            handoff.put("a");
            handoff.put("b");
            assertThat(handoff.pending()).isEqualTo(2);

            handoff.discardPending();

            assertThat(handoff.pending()).isZero();
        }
    }

    @Nested
    @DisplayName("a healthy run is unaffected")
    class HealthyRun {

        @Test
        @DisplayName("a slow but live consumer still gets back-pressure, not an abort")
        void slowConsumerIsNotMistakenForADeadOne() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                var handoff = new Handoff<String>("writers", 1, 1, POLL_MILLIS);
                var producer = new Producer(handoff, 5);

                // Consumer that is far slower than the liveness poll interval.
                Thread.sleep(300);
                for (int i = 0; i < 5; i++) {
                    Thread.sleep(50);
                    assertThat(handoff.take()).isEqualTo("item-" + i);
                }

                producer.joinWithin(Duration.ofSeconds(5));
                assertThat(producer.thrown.get()).isNull();
                assertThat(producer.put.get()).isEqualTo(5);
            });
        }

        @Test
        @DisplayName("poison-pill shutdown still drains every item and stops every consumer")
        void poisonPillShutdownStillWorks() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                int consumers = 4;
                int items = 500;
                var handoff = new Handoff<String>("writers", 8, consumers, POLL_MILLIS);
                var consumed = new AtomicInteger();
                var done = new CountDownLatch(consumers);

                for (int c = 0; c < consumers; c++) {
                    Thread.ofVirtual().start(() -> {
                        try {
                            while (true) {
                                String item = handoff.take();
                                if ("POISON".equals(item)) break;
                                consumed.incrementAndGet();
                            }
                            handoff.consumerFinished();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }

                for (int i = 0; i < items; i++) {
                    handoff.put("item-" + i);
                }
                for (int c = 0; c < consumers; c++) {
                    handoff.putSentinel("POISON");
                }

                assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(consumed.get()).isEqualTo(items);
                assertThat(handoff.liveConsumers()).isZero();
                assertThat(handoff.fatalFailure()).isNull();
                assertThat(handoff.pending()).isZero();
            });
        }
    }

    @Nested
    @DisplayName("the writer sentinel contract")
    class WriterSentinel {

        @Test
        @DisplayName("the writer sentinel is still a Success with id == -1")
        void sentinelIsSuccessWithMinusOneId() {
            CompressionResult.Success sentinel = CompressionPipeline.writerSentinel();

            assertThat(sentinel.id()).isEqualTo(-1);
            assertThat(sentinel.compressedData()).isEmpty();
            assertThat(sentinel.processingTime()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("writers stop on the sentinel and process everything before it")
        void writersStopOnTheSentinel() {
            assertTimeoutPreemptively(TIMEOUT, () -> {
                var handoff = new Handoff<CompressionResult.Success>("writers", 4, 1, POLL_MILLIS);
                var written = new AtomicInteger();
                var stopped = new CountDownLatch(1);

                Thread.ofVirtual().start(() -> {
                    try {
                        while (true) {
                            CompressionResult.Success result = handoff.take();
                            if (result.id() == -1) break; // the sentinel check the writers use
                            written.incrementAndGet();
                        }
                        handoff.consumerFinished();
                        stopped.countDown();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

                for (int i = 0; i < 10; i++) {
                    handoff.put(new CompressionResult.Success(i, 1, "f" + i, new byte[]{1}, 10, 5, Duration.ZERO));
                }
                handoff.putSentinel(CompressionPipeline.writerSentinel());

                assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(written.get()).isEqualTo(10);
            });
        }
    }
}
