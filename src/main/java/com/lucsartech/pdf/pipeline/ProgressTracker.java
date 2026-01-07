package com.lucsartech.pdf.pipeline;

import com.lucsartech.pdf.compression.CompressionResult;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // Dead letter queue for failed records
    private final List<FailedRecord> failedRecords = new CopyOnWriteArrayList<>();

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
    }

    public void recordResult(CompressionResult result) {
        switch (result) {
            case CompressionResult.Success success -> {
                compressedCount.increment();
                originalBytes.add(success.originalSize());
                compressedBytes.add(success.compressedSize());
                totalProcessingTimeMs.add(success.processingTime().toMillis());
                addActivity(ActivityEntry.compressed(success.id(), success.filename(), success.originalSize(),
                        success.compressedSize(), success.processingTime().toMillis()));
            }
            case CompressionResult.Skipped skipped -> {
                skippedCount.increment();
                skippedBytes.add(skipped.size());
                addActivity(ActivityEntry.skipped(skipped.id(), skipped.filename(), skipped.size()));
            }
            case CompressionResult.Failure failure -> {
                errorCount.increment();
                failedRecords.add(FailedRecord.of(failure.id(), failure.errorMessage()));
                addActivity(ActivityEntry.failed(failure.id(), failure.filename()));
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
        errorCount.increment();
        failedRecords.add(FailedRecord.of(id, t));
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
    public List<FailedRecord> failedRecords() { return List.copyOf(failedRecords); }
    public List<Long> failedIds() { return failedRecords.stream().map(FailedRecord::id).toList(); }

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
                compressionRatio(),
                savingsPercent(),
                progressPercent(),
                elapsedTime().toSeconds(),
                recordsPerSecond(),
                mbPerSecond(),
                averageProcessingTimeMs(),
                failedRecords.size(),
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
            double compressionRatio,
            double savingsPercent,
            double progressPercent,
            long elapsedSeconds,
            double recordsPerSecond,
            double mbPerSecond,
            long avgProcessingTimeMs,
            int dlqSize,
            boolean completed
    ) {
        public double originalMb() { return originalBytes / 1024.0 / 1024.0; }
        public double compressedMb() { return compressedBytes / 1024.0 / 1024.0; }
        public double initialDbMb() { return initialDbSizeBytes / 1024.0 / 1024.0; }
        public double finalDbMb() { return finalDbSizeBytes / 1024.0 / 1024.0; }
        public double projectedFinalMb() { return projectedFinalBytes / 1024.0 / 1024.0; }

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
