package com.lucsartech.squish.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for the two watchdog defects. Both exercise the package-private seams of
 * {@link WatchdogService} directly, so no database, no Spring context and no network are needed
 * (a {@code WatchdogService} instance cannot be built in a test: its constructor opens a HikariCP
 * pool against Oracle).
 */
class WatchdogServiceTest {

    private final List<ExecutorService> toClose = new ArrayList<>();

    @AfterEach
    void tearDown() {
        toClose.forEach(ExecutorService::shutdownNow);
    }

    private <T extends ExecutorService> T register(T executor) {
        toClose.add(executor);
        return executor;
    }

    // ---------------------------------------------------------------------------------------
    // DEFECT 1: a throwable escaping the scheduled cycle silently cancels the schedule forever
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("runGuarded (scheduled-cycle guard)")
    class GuardTests {

        @Test
        @DisplayName("baseline: an unguarded throwing task is cancelled by the scheduler after one run")
        void unguardedThrowingTaskIsSilentlyCancelled() throws Exception {
            ScheduledExecutorService scheduler = register(Executors.newSingleThreadScheduledExecutor());
            AtomicInteger runs = new AtomicInteger();

            scheduler.scheduleAtFixedRate(() -> {
                runs.incrementAndGet();
                throw new IllegalStateException("boom");
            }, 0, 10, TimeUnit.MILLISECONDS);

            Thread.sleep(300);

            // This is the ScheduledExecutorService contract that makes the guard necessary.
            assertThat(runs.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("an Exception thrown by the cycle body does not cancel the schedule")
        void exceptionDoesNotCancelSchedule() throws Exception {
            ScheduledExecutorService scheduler = register(Executors.newSingleThreadScheduledExecutor());
            AtomicInteger runs = new AtomicInteger();
            AtomicInteger fatalCalls = new AtomicInteger();

            scheduler.scheduleAtFixedRate(
                    () -> WatchdogService.runGuarded(
                            runs.incrementAndGet(),
                            () -> { throw new RuntimeException("transient DB blip"); },
                            t -> fatalCalls.incrementAndGet()),
                    0, 10, TimeUnit.MILLISECONDS);

            assertThat(awaitAtLeast(runs, 5, 3000)).isTrue();
            // Transient failures must never invoke the fatal (process-terminating) path.
            assertThat(fatalCalls.get()).isZero();
        }

        @Test
        @DisplayName("an Error is logged, routed to the fatal handler, and never escapes the task")
        void errorIsRoutedToFatalHandlerAndDoesNotEscape() {
            AtomicReference<Throwable> fatal = new AtomicReference<>();
            OutOfMemoryError oom = new OutOfMemoryError("Java heap space");

            assertThatCode(() -> WatchdogService.runGuarded(
                    1L,
                    () -> { throw oom; },
                    fatal::set))
                    .doesNotThrowAnyException();

            assertThat(fatal.get()).isSameAs(oom);
        }

        @Test
        @DisplayName("an Error does not cancel the schedule either (the fatal handler decides)")
        void errorDoesNotCancelSchedule() throws Exception {
            ScheduledExecutorService scheduler = register(Executors.newSingleThreadScheduledExecutor());
            AtomicInteger runs = new AtomicInteger();
            AtomicInteger fatalCalls = new AtomicInteger();

            scheduler.scheduleAtFixedRate(
                    () -> WatchdogService.runGuarded(
                            runs.incrementAndGet(),
                            () -> { throw new OutOfMemoryError("Java heap space"); },
                            t -> fatalCalls.incrementAndGet()),
                    0, 10, TimeUnit.MILLISECONDS);

            assertThat(awaitAtLeast(runs, 3, 3000)).isTrue();
            assertThat(fatalCalls.get()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("a fatal handler that itself throws still does not escape the task")
        void failingFatalHandlerDoesNotEscape() {
            assertThatCode(() -> WatchdogService.runGuarded(
                    1L,
                    () -> { throw new StackOverflowError("deep"); },
                    t -> { throw new IllegalStateException("handler blew up"); }))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a clean cycle body runs untouched")
        void successfulBodyRunsNormally() {
            AtomicInteger runs = new AtomicInteger();
            AtomicInteger fatalCalls = new AtomicInteger();

            WatchdogService.runGuarded(1L, runs::incrementAndGet, t -> fatalCalls.incrementAndGet());

            assertThat(runs.get()).isEqualTo(1);
            assertThat(fatalCalls.get()).isZero();
        }
    }

    // ---------------------------------------------------------------------------------------
    // DEFECT 2: the producer must be throttled so the whole cycle's BLOBs never land in heap
    // ---------------------------------------------------------------------------------------

    @Nested
    @DisplayName("submitThrottled (producer backpressure)")
    class BackpressureTests {

        private static final int RECORDS = 200;

        @Test
        @DisplayName("never more than `maxInFlight` PDF byte[] are alive at once")
        void producerIsThrottledToTheInFlightBound() throws Exception {
            int maxInFlight = 4;
            int workerThreads = 2;
            Semaphore inFlight = new Semaphore(maxInFlight);
            Semaphore workers = new Semaphore(workerThreads);
            Executor executor = register(Executors.newVirtualThreadPerTaskExecutor());

            AtomicInteger currentlyInFlight = new AtomicInteger();
            AtomicInteger peakInFlight = new AtomicInteger();
            AtomicInteger peakConcurrentWorkers = new AtomicInteger();
            AtomicInteger currentWorkers = new AtomicInteger();
            AtomicInteger completed = new AtomicInteger();

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < RECORDS; i++) {
                futures.add(WatchdogService.submitThrottled(
                        inFlight, workers, executor,
                        () -> {
                            // Stands in for rs.getBinaryStream(...).readAllBytes(): this is the
                            // heap allocation, and it must only happen under an in-flight permit.
                            peak(peakInFlight, currentlyInFlight.incrementAndGet());
                            return new byte[]{1, 2, 3};
                        },
                        data -> {
                            peak(peakConcurrentWorkers, currentWorkers.incrementAndGet());
                            sleep(2);
                            currentWorkers.decrementAndGet();
                            completed.incrementAndGet();
                            // Drop the "reference" before the permit is released.
                            currentlyInFlight.decrementAndGet();
                        }));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            assertThat(peakInFlight.get())
                    .as("producer must block once %d PDFs are in flight", maxInFlight)
                    .isLessThanOrEqualTo(maxInFlight);
            assertThat(peakInFlight.get())
                    .as("the pipeline must still overlap producer and workers")
                    .isGreaterThan(1);
            assertThat(peakConcurrentWorkers.get())
                    .as("compression concurrency stays capped by worker-threads")
                    .isLessThanOrEqualTo(workerThreads);
            assertThat(completed.get()).isEqualTo(RECORDS);
            assertThat(futures).hasSize(RECORDS);
            assertThat(inFlight.availablePermits()).isEqualTo(maxInFlight);
            assertThat(workers.availablePermits()).isEqualTo(workerThreads);
        }

        @Test
        @DisplayName("a single permit serialises the producer completely (bound of 1)")
        void boundOfOneAllowsExactlyOneInFlight() throws Exception {
            Semaphore inFlight = new Semaphore(1);
            Semaphore workers = new Semaphore(4);
            Executor executor = register(Executors.newVirtualThreadPerTaskExecutor());

            AtomicInteger current = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                futures.add(WatchdogService.submitThrottled(
                        inFlight, workers, executor,
                        () -> { peak(peak, current.incrementAndGet()); return new byte[16]; },
                        data -> { sleep(1); current.decrementAndGet(); }));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            assertThat(peak.get()).isEqualTo(1);
            assertThat(inFlight.availablePermits()).isEqualTo(1);
        }

        @Test
        @DisplayName("a failing BLOB read releases its permit (no leak, no starvation)")
        void failingBlobLoadReleasesPermit() {
            Semaphore inFlight = new Semaphore(1);
            Semaphore workers = new Semaphore(1);
            Executor executor = register(Executors.newVirtualThreadPerTaskExecutor());

            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> WatchdogService.submitThrottled(
                        inFlight, workers, executor,
                        () -> { throw new IOException("LOB stream reset"); },
                        data -> { }))
                        .isInstanceOf(IOException.class);

                // If the permit leaked, the next iteration would block forever.
                assertThat(inFlight.availablePermits()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a record whose processing throws still releases both permits")
        void failingRecordReleasesPermits() {
            Semaphore inFlight = new Semaphore(2);
            Semaphore workers = new Semaphore(2);
            Executor executor = register(Executors.newVirtualThreadPerTaskExecutor());

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                CompletableFuture<Void> f = assertDoesNotThrowSubmit(
                        inFlight, workers, executor,
                        () -> new byte[8],
                        data -> { throw new IllegalStateException("corrupt pdf"); });
                futures.add(f.exceptionally(t -> null));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            assertThat(inFlight.availablePermits()).isEqualTo(2);
            assertThat(workers.availablePermits()).isEqualTo(2);
        }

        @Test
        @DisplayName("a rejected submission releases its permit")
        void rejectedSubmissionReleasesPermit() {
            Semaphore inFlight = new Semaphore(3);
            Semaphore workers = new Semaphore(3);
            ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
            executor.shutdown();

            assertThatThrownBy(() -> WatchdogService.submitThrottled(
                    inFlight, workers, executor,
                    () -> new byte[8],
                    data -> { }))
                    .isInstanceOf(RejectedExecutionException.class);

            assertThat(inFlight.availablePermits()).isEqualTo(3);
        }

        @Test
        @DisplayName("an interrupted producer throws InterruptedException without reading the BLOB")
        void interruptedProducerDoesNotAllocate() throws Exception {
            Semaphore inFlight = new Semaphore(1);
            Semaphore workers = new Semaphore(1);
            Executor executor = register(Executors.newVirtualThreadPerTaskExecutor());
            AtomicInteger loads = new AtomicInteger();
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            Thread producer = new Thread(() -> {
                inFlight.acquireUninterruptibly(); // the only permit: the next acquire must block
                Thread.currentThread().interrupt();
                try {
                    WatchdogService.submitThrottled(
                            inFlight, workers, executor,
                            () -> { loads.incrementAndGet(); return new byte[8]; },
                            data -> { });
                } catch (Throwable t) {
                    thrown.set(t);
                } finally {
                    done.countDown();
                }
            });
            producer.start();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
            // No permit was granted, so no PDF was ever materialised.
            assertThat(loads.get()).isZero();
            assertThat(inFlight.availablePermits()).isZero();
        }

        private CompletableFuture<Void> assertDoesNotThrowSubmit(Semaphore inFlight,
                                                                 Semaphore workers,
                                                                 Executor executor,
                                                                 WatchdogService.BlobLoader loader,
                                                                 java.util.function.Consumer<byte[]> processor) {
            try {
                return WatchdogService.submitThrottled(inFlight, workers, executor, loader, processor);
            } catch (Exception e) {
                throw new AssertionError("submitThrottled should not have thrown", e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    private static void peak(AtomicInteger peak, int candidate) {
        peak.accumulateAndGet(candidate, Math::max);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean awaitAtLeast(AtomicInteger counter, int target, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (counter.get() >= target) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }
}
