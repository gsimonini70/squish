package com.lucsartech.squish.pipeline;

import com.lucsartech.squish.compression.CompressionResult;
import com.lucsartech.squish.metrics.SquishMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe progress tracking for the compression pipeline.
 * Uses LongAdder for high-throughput counters and atomic operations for state.
 */
public final class ProgressTracker {

    // Processing counters (using LongAdder for better concurrent performance)
    private final LongAdder readCount = new LongAdder();
    private final LongAdder compressedCount = new LongAdder();
    private final LongAdder skippedCount = new LongAdder();
    private final LongAdder updatedCount = new LongAdder();
    private final LongAdder errorCount = new LongAdder();

    // Size tracking
    private final LongAdder originalBytes = new LongAdder();
    private final LongAdder compressedBytes = new LongAdder();
    private final LongAdder skippedBytes = new LongAdder();
    private final LongAdder totalProcessingTimeMs = new LongAdder();

    // Database metrics
    private final AtomicLong totalRecords = new AtomicLong(0);
    private final AtomicLong initialDbSizeBytes = new AtomicLong(0);
    private final AtomicLong finalDbSizeBytes = new AtomicLong(0);

    // State
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private volatile Instant startTime;
    private volatile Instant endTime;

    /**
     * Maximum number of {@link FailedRecord}s retained in memory for the report/dashboard.
     *
     * <p>A run against a broken schema can fail every single record (hundreds of thousands),
     * so the dead-letter list must be bounded. Once the cap is reached, further failures are
     * counted but their records are dropped, and {@link #isFailedRecordsTruncated()} flips to
     * {@code true}. The <em>first</em> failures are the ones retained: they are the most
     * diagnostic (a systematic failure shows up immediately), retention is stable across
     * dashboard polls, and dropping is O(1). The most <em>recent</em> failures remain visible
     * through {@link #recentActivity()}, which is its own capped ring buffer.
     */
    public static final int MAX_FAILED_RECORDS = 1_000;

    // Dead letter queue for failed records - BOUNDED at MAX_FAILED_RECORDS.
    // ConcurrentLinkedQueue: O(1) append. (The previous CopyOnWriteArrayList copied the whole
    // backing array on every add, i.e. O(n^2) total for n failures, and was unbounded.)
    // The exact, never-truncated total is errorCount; retainedFailures is the size of the queue
    // (kept separately because ConcurrentLinkedQueue.size() is O(n)).
    private final Queue<FailedRecord> failedRecords = new ConcurrentLinkedQueue<>();
    private final AtomicInteger retainedFailures = new AtomicInteger(0);
    private final AtomicBoolean failedRecordsTruncated = new AtomicBoolean(false);

    // Recent activity log (circular buffer)
    private static final int MAX_RECENT_ACTIVITY = 50;
    private final LinkedList<ActivityEntry> recentActivity = new LinkedList<>();
    private final ReentrantLock activityLock = new ReentrantLock();

    /**
     * Record for tracking recent activity.
     */
    public record ActivityEntry(long id, String filename, String status, long originalSize, long compressedSize, double savingsPercent, long timeMs) {
        public static ActivityEntry compressed(long id, String filename, long original, long compressed, long timeMs) {
            double savings = original > 0 ? (1.0 - (double) compressed / original) * 100.0 : 0;
            return new ActivityEntry(id, filename, "COMPRESSED", original, compressed, savings, timeMs);
        }
        public static ActivityEntry skipped(long id, String filename, long size) {
            return new ActivityEntry(id, filename, "SKIPPED", size, size, 0, 0);
        }
        public static ActivityEntry failed(long id, String filename) {
            return new ActivityEntry(id, filename, "FAILED", 0, 0, 0, 0);
        }
    }

    /**
     * Record for tracking failed compression attempts.
     */
    public record FailedRecord(long id, String error, Instant timestamp) {
        public static FailedRecord of(long id, Throwable t) {
            return new FailedRecord(id, t.getMessage(), Instant.now());
        }

        public static FailedRecord of(long id, String error) {
            return new FailedRecord(id, error, Instant.now());
        }
    }

    // ========== Recording Methods ==========

    public void recordRead() {
        readCount.increment();
        // Prometheus metrics
        SquishMetrics.getInstance().recordRead();
    }

    public void recordResult(CompressionResult result) {
        var metrics = SquishMetrics.getInstance();

        switch (result) {
            case CompressionResult.Success success -> {
                compressedCount.increment();
                originalBytes.add(success.originalSize());
                compressedBytes.add(success.compressedSize());
                totalProcessingTimeMs.add(success.processingTime().toMillis());
                addActivity(ActivityEntry.compressed(success.id(), success.filename(), success.originalSize(),
                        success.compressedSize(), success.processingTime().toMillis()));
                // Prometheus metrics
                metrics.recordCompressed(success.originalSize(), success.compressedSize(),
                        success.processingTime().toMillis());
            }
            case CompressionResult.Skipped skipped -> {
                skippedCount.increment();
                skippedBytes.add(skipped.size());
                addActivity(ActivityEntry.skipped(skipped.id(), skipped.filename(), skipped.size()));
                // Prometheus metrics
                metrics.recordSkipped();
            }
            case CompressionResult.Failure failure -> {
                addFailure(FailedRecord.of(failure.id(), failure.errorMessage()));
                addActivity(ActivityEntry.failed(failure.id(), failure.filename()));
                // Prometheus metrics
                metrics.recordFailed();
            }
        }
    }

    private void addActivity(ActivityEntry entry) {
        activityLock.lock();
        try {
            recentActivity.addFirst(entry);
            while (recentActivity.size() > MAX_RECENT_ACTIVITY) {
                recentActivity.removeLast();
            }
        } finally {
            activityLock.unlock();
        }
    }

    public List<ActivityEntry> recentActivity() {
        activityLock.lock();
        try {
            return List.copyOf(recentActivity);
        } finally {
            activityLock.unlock();
        }
    }

    public void recordUpdate() {
        updatedCount.increment();
    }

    public void recordError(long id, Throwable t) {
        addFailure(FailedRecord.of(id, t));
    }

    /**
     * Count a failure (always exact) and retain its record while under {@link #MAX_FAILED_RECORDS}.
     *
     * <p>Single funnel for every failure so that {@link #errorCount()} — the number the report and
     * the dashboard display — can never drift from the number of failures that actually happened,
     * even though the retained list is capped. Appending is O(1) and allocation-free once capped.
     */
    private void addFailure(FailedRecord record) {
        errorCount.increment();

        int retained = retainedFailures.get();
        while (retained < MAX_FAILED_RECORDS) {
            if (retainedFailures.compareAndSet(retained, retained + 1)) {
                failedRecords.add(record);
                return;
            }
            retained = retainedFailures.get();
        }
        failedRecordsTruncated.set(true);
    }

    // ========== State Management ==========

    public void setInitialStats(long recordCount, long dbSizeBytes) {
        totalRecords.set(recordCount);
        initialDbSizeBytes.set(dbSizeBytes);
    }

    public void setFinalDbSize(long sizeBytes) {
        finalDbSizeBytes.set(sizeBytes);
    }

    public void markStarted() {
        startTime = Instant.now();
    }

    public void markCompleted() {
        endTime = Instant.now();
        completed.set(true);
    }

    // ========== Computed Metrics ==========

    public double compressionRatio() {
        long orig = originalBytes.sum();
        long comp = compressedBytes.sum();
        return orig > 0 ? (double) comp / orig : 1.0;
    }

    public double savingsPercent() {
        return (1.0 - compressionRatio()) * 100.0;
    }

    public double progressPercent() {
        long total = totalRecords.get();
        return total > 0 ? (double) updatedCount.sum() / total * 100.0 : 0.0;
    }

    public long projectedFinalSizeBytes() {
        long processed = originalBytes.sum();
        long compressed = compressedBytes.sum();
        long initial = initialDbSizeBytes.get();

        if (processed == 0) return initial;

        double ratio = (double) compressed / processed;
        return (long) (initial * ratio);
    }

    /**
     * Calculate current database size based on compression savings.
     * This works in both batch and watchdog modes.
     * Formula: initial - (originalBytes - compressedBytes) = initial - savings
     */
    public long currentDbSizeBytes() {
        long initial = initialDbSizeBytes.get();
        long savedBytes = originalBytes.sum() - compressedBytes.sum();
        return initial - savedBytes;
    }

    public Duration elapsedTime() {
        if (startTime == null) return Duration.ZERO;
        Instant end = endTime != null ? endTime : Instant.now();
        return Duration.between(startTime, end);
    }

    public double recordsPerSecond() {
        long elapsed = elapsedTime().toSeconds();
        return elapsed > 0 ? (double) updatedCount.sum() / elapsed : 0.0;
    }

    public double mbPerSecond() {
        long elapsed = elapsedTime().toSeconds();
        double origMb = originalBytes.sum() / 1024.0 / 1024.0;
        return elapsed > 0 ? origMb / elapsed : 0.0;
    }

    public long averageProcessingTimeMs() {
        long count = compressedCount.sum();
        return count > 0 ? totalProcessingTimeMs.sum() / count : 0;
    }

    // ========== Getters ==========

    public long readCount() { return readCount.sum(); }
    public long compressedCount() { return compressedCount.sum(); }
    public long skippedCount() { return skippedCount.sum(); }
    public long updatedCount() { return updatedCount.sum(); }
    public long errorCount() { return errorCount.sum(); }
    public long originalBytes() { return originalBytes.sum(); }
    public long compressedBytes() { return compressedBytes.sum(); }
    public long skippedBytes() { return skippedBytes.sum(); }
    public long totalRecords() { return totalRecords.get(); }
    public long initialDbSizeBytes() { return initialDbSizeBytes.get(); }
    public long finalDbSizeBytes() { return finalDbSizeBytes.get(); }
    public boolean isCompleted() { return completed.get(); }
    public Instant startTime() { return startTime; }
    public Instant endTime() { return endTime; }
    /**
     * The retained failures, at most {@link #MAX_FAILED_RECORDS} of them.
     * Use {@link #failedCount()} for the exact number of failures and
     * {@link #isFailedRecordsTruncated()} to know whether this list is partial.
     */
    public List<FailedRecord> failedRecords() { return List.copyOf(failedRecords); }

    /**
     * The IDs of the retained failures, at most {@link #MAX_FAILED_RECORDS} of them.
     * NOTE: {@code failedIds().size()} is NOT the failure count once truncation kicks in -
     * callers that display a total must use {@link #failedCount()}.
     */
    public List<Long> failedIds() { return failedRecords.stream().map(FailedRecord::id).toList(); }

    /** Exact total number of failures, never truncated (== {@link #errorCount()}). */
    public long failedCount() { return errorCount.sum(); }

    /** Number of failure records actually retained (capped at {@link #MAX_FAILED_RECORDS}). */
    public int retainedFailedRecords() { return retainedFailures.get(); }

    /** True when at least one failure record was dropped because the cap was reached. */
    public boolean isFailedRecordsTruncated() { return failedRecordsTruncated.get(); }

    /**
     * Create a snapshot of current metrics for serialization.
     */
    public Snapshot snapshot() {
        return new Snapshot(
                readCount.sum(),
                compressedCount.sum(),
                skippedCount.sum(),
                updatedCount.sum(),
                errorCount.sum(),
                originalBytes.sum(),
                compressedBytes.sum(),
                skippedBytes.sum(),
                totalRecords.get(),
                initialDbSizeBytes.get(),
                finalDbSizeBytes.get(),
                projectedFinalSizeBytes(),
                currentDbSizeBytes(),
                compressionRatio(),
                savingsPercent(),
                progressPercent(),
                elapsedTime().toSeconds(),
                recordsPerSecond(),
                mbPerSecond(),
                averageProcessingTimeMs(),
                errorCount.sum(),          // dlqSize: EXACT failure total, even when records were dropped
                retainedFailures.get(),
                failedRecordsTruncated.get(),
                completed.get()
        );
    }

    /**
     * Immutable snapshot of progress metrics.
     */
    public record Snapshot(
            long read,
            long compressed,
            long skipped,
            long updated,
            long errors,
            long originalBytes,
            long compressedBytes,
            long skippedBytes,
            long totalRecords,
            long initialDbSizeBytes,
            long finalDbSizeBytes,
            long projectedFinalBytes,
            long currentDbSizeBytes,
            double compressionRatio,
            double savingsPercent,
            double progressPercent,
            long elapsedSeconds,
            double recordsPerSecond,
            double mbPerSecond,
            long avgProcessingTimeMs,
            /** Exact number of failures - never capped, even when failure records were dropped. */
            long dlqSize,
            /** Number of failure records actually retained (<= {@link ProgressTracker#MAX_FAILED_RECORDS}). */
            int dlqRetained,
            /** True when dlqRetained < dlqSize, i.e. the retained failure list is partial. */
            boolean dlqTruncated,
            boolean completed
    ) {
        public double originalMb() { return originalBytes / 1024.0 / 1024.0; }
        public double compressedMb() { return compressedBytes / 1024.0 / 1024.0; }
        public double initialDbMb() { return initialDbSizeBytes / 1024.0 / 1024.0; }
        public double finalDbMb() { return finalDbSizeBytes / 1024.0 / 1024.0; }
        public double projectedFinalMb() { return projectedFinalBytes / 1024.0 / 1024.0; }
        public double currentDbMb() { return currentDbSizeBytes / 1024.0 / 1024.0; }

        public String elapsedFormatted() {
            long h = elapsedSeconds / 3600;
            long m = (elapsedSeconds % 3600) / 60;
            long s = elapsedSeconds % 60;
            return h > 0
                    ? String.format("%d:%02d:%02d", h, m, s)
                    : String.format("%d:%02d", m, s);
        }
    }
}
