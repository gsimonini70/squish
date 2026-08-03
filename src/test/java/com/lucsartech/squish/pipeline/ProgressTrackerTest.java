package com.lucsartech.squish.pipeline;

import com.lucsartech.squish.compression.CompressionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.lucsartech.squish.pipeline.ProgressTracker.MAX_FAILED_RECORDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Regression tests for the unbounded dead-letter list.
 *
 * <p>{@code failedRecords} used to be an unbounded {@link java.util.concurrent.CopyOnWriteArrayList}:
 * O(n^2) copying and no memory ceiling on a run where every record fails. It is now a bounded
 * O(1)-append queue, and the failure COUNT must stay exact even when records are dropped.
 */
class ProgressTrackerTest {

    private static CompressionResult.Failure failure(long id) {
        return CompressionResult.Failure.of(id, 1, "doc-" + id + ".pdf", "corrupt xref table");
    }

    @Nested
    @DisplayName("the failure count is exact, always")
    class ExactCount {

        @Test
        @DisplayName("the count survives truncation: 10x the cap in, all of it counted")
        void countIsExactWellPastTheCap() {
            var tracker = new ProgressTracker();
            int failures = MAX_FAILED_RECORDS * 10;

            for (int i = 0; i < failures; i++) {
                tracker.recordResult(failure(i));
            }

            assertThat(tracker.errorCount()).isEqualTo(failures);
            assertThat(tracker.failedCount()).isEqualTo(failures);
            assertThat(tracker.snapshot().dlqSize()).isEqualTo(failures);
        }

        @Test
        @DisplayName("both failure paths - recordResult(Failure) and recordError - are counted")
        void bothFailurePathsCount() {
            var tracker = new ProgressTracker();

            tracker.recordResult(failure(1));
            tracker.recordResult(failure(2));
            tracker.recordError(-997, new IllegalStateException("writer died"));

            assertThat(tracker.failedCount()).isEqualTo(3);
            assertThat(tracker.failedRecords()).hasSize(3);
            assertThat(tracker.failedIds()).containsExactly(1L, 2L, -997L);
        }

        @Test
        @DisplayName("successes and skips are not counted as failures")
        void nonFailuresAreNotCounted() {
            var tracker = new ProgressTracker();

            tracker.recordResult(new CompressionResult.Success(1, 1, "a.pdf", new byte[]{1}, 100, 50, Duration.ofMillis(5)));
            tracker.recordResult(CompressionResult.Skipped.notPdf(2, 1, "b.txt", 10));
            tracker.recordResult(failure(3));

            assertThat(tracker.failedCount()).isEqualTo(1);
            assertThat(tracker.failedRecords()).hasSize(1);
            assertThat(tracker.snapshot().dlqSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("the count stays exact under concurrent failures from many threads")
        void countIsExactUnderConcurrency() {
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                var tracker = new ProgressTracker();
                int threads = 8;
                int perThread = 2_000;
                var start = new CountDownLatch(1);
                var done = new CountDownLatch(threads);

                for (int t = 0; t < threads; t++) {
                    final int base = t * perThread;
                    Thread.ofVirtual().start(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                tracker.recordResult(failure(base + i));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }

                start.countDown();
                assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();

                assertThat(tracker.failedCount()).isEqualTo((long) threads * perThread);
                // The cap is never exceeded, not even by a race between two threads at the boundary.
                assertThat(tracker.retainedFailedRecords()).isEqualTo(MAX_FAILED_RECORDS);
                assertThat(tracker.failedRecords()).hasSize(MAX_FAILED_RECORDS);
                assertThat(tracker.isFailedRecordsTruncated()).isTrue();
            });
        }
    }

    @Nested
    @DisplayName("the retained failure list is capped")
    class Cap {

        @Test
        @DisplayName("the list stops growing at the cap and reports itself as truncated")
        void listIsCappedAndFlaggedAsTruncated() {
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                var tracker = new ProgressTracker();
                int failures = MAX_FAILED_RECORDS + 5_000;

                for (int i = 0; i < failures; i++) {
                    tracker.recordError(i, new RuntimeException("boom " + i));
                }

                assertThat(tracker.failedRecords()).hasSize(MAX_FAILED_RECORDS);
                assertThat(tracker.failedIds()).hasSize(MAX_FAILED_RECORDS);
                assertThat(tracker.retainedFailedRecords()).isEqualTo(MAX_FAILED_RECORDS);
                assertThat(tracker.isFailedRecordsTruncated()).isTrue();

                // ...while the total remains exact.
                assertThat(tracker.failedCount()).isEqualTo(failures);

                var snapshot = tracker.snapshot();
                assertThat(snapshot.dlqSize()).isEqualTo(failures);
                assertThat(snapshot.dlqRetained()).isEqualTo(MAX_FAILED_RECORDS);
                assertThat(snapshot.dlqTruncated()).isTrue();
                assertThat(snapshot.errors()).isEqualTo(failures);
            });
        }

        @Test
        @DisplayName("the retained records are the first failures, in order")
        void retainsTheFirstFailuresInOrder() {
            var tracker = new ProgressTracker();

            for (int i = 0; i < MAX_FAILED_RECORDS + 100; i++) {
                tracker.recordResult(failure(i));
            }

            var ids = tracker.failedIds();
            assertThat(ids).hasSize(MAX_FAILED_RECORDS);
            assertThat(ids.get(0)).isZero();
            assertThat(ids.get(MAX_FAILED_RECORDS - 1)).isEqualTo(MAX_FAILED_RECORDS - 1);
            assertThat(ids).doesNotContain((long) MAX_FAILED_RECORDS);
        }

        @Test
        @DisplayName("below the cap nothing is dropped and nothing is flagged")
        void belowTheCapNothingIsTruncated() {
            var tracker = new ProgressTracker();

            for (int i = 0; i < 10; i++) {
                tracker.recordResult(failure(i));
            }

            assertThat(tracker.failedRecords()).hasSize(10);
            assertThat(tracker.isFailedRecordsTruncated()).isFalse();
            assertThat(tracker.failedCount()).isEqualTo(10);

            var snapshot = tracker.snapshot();
            assertThat(snapshot.dlqSize()).isEqualTo(10);
            assertThat(snapshot.dlqRetained()).isEqualTo(10);
            assertThat(snapshot.dlqTruncated()).isFalse();
        }

        @Test
        @DisplayName("a fresh tracker has no failures")
        void freshTrackerIsEmpty() {
            var tracker = new ProgressTracker();

            assertThat(tracker.failedRecords()).isEmpty();
            assertThat(tracker.failedIds()).isEmpty();
            assertThat(tracker.failedCount()).isZero();
            assertThat(tracker.retainedFailedRecords()).isZero();
            assertThat(tracker.isFailedRecordsTruncated()).isFalse();
            assertThat(tracker.snapshot().dlqSize()).isZero();
        }
    }

    @Nested
    @DisplayName("appending failures is O(1), not O(n)")
    class AppendCost {

        /**
         * The old CopyOnWriteArrayList copied its whole backing array on every add, so recording
         * 200k failures took quadratic time. This is a coarse guard - it only has to catch a
         * return to quadratic behaviour, not micro-regressions.
         */
        @Test
        @DisplayName("200k failures are recorded well within a preemptive timeout")
        void manyFailuresAreCheapToRecord() {
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                var tracker = new ProgressTracker();
                int failures = 200_000;

                for (int i = 0; i < failures; i++) {
                    tracker.recordError(i, new RuntimeException("boom"));
                }

                assertThat(tracker.failedCount()).isEqualTo(failures);
                assertThat(tracker.retainedFailedRecords()).isEqualTo(MAX_FAILED_RECORDS);
            });
        }
    }
}
