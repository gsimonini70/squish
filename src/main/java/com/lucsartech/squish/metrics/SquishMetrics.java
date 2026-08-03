package com.lucsartech.squish.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus metrics for Squish PDF Compression Engine.
 * Exposes metrics in Prometheus format at /metrics endpoint.
 */
public final class SquishMetrics {

    private static final Logger log = LoggerFactory.getLogger(SquishMetrics.class);

    private static SquishMetrics instance;

    private final PrometheusMeterRegistry registry;

    // Counters - monotonically increasing values
    private final Counter recordsRead;
    private final Counter recordsCompressed;
    private final Counter recordsSkipped;
    private final Counter recordsFailed;
    private final Counter bytesOriginal;
    private final Counter bytesCompressed;

    // Gauges - current values (backed by AtomicLong)
    private final AtomicLong queueSize = new AtomicLong(0);
    private final AtomicLong activeWorkers = new AtomicLong(0);
    private final AtomicLong currentCycle = new AtomicLong(0);

    // Timers - for measuring durations
    private final Timer compressionTimer;

    // On-demand REST compression tracking (process-lifetime)
    private final Counter onDemandCalls;
    private final Timer onDemandTimer;
    private final AtomicLong onDemandCount = new AtomicLong(0);
    private final AtomicLong onDemandTotalMs = new AtomicLong(0);
    private volatile String onDemandLastFilename;
    private volatile double onDemandLastSavingsPercent;
    private volatile boolean onDemandLastSuccess;

    // System metrics
    private final OperatingSystemMXBean osMxBean;
    private final MemoryMXBean memoryMxBean;

    private SquishMetrics() {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        // Add common tags. Version resolves at runtime; null in fat-jar/IDE -> "dev".
        String version = SquishMetrics.class.getPackage().getImplementationVersion();
        registry.config().commonTags(
            "application", "squish",
            "version", version != null ? version : "dev"
        );

        // Initialize counters
        this.recordsRead = Counter.builder("squish_records_read_total")
                .description("Total number of records read from database")
                .register(registry);

        this.recordsCompressed = Counter.builder("squish_records_compressed_total")
                .description("Total number of PDFs successfully compressed")
                .register(registry);

        this.recordsSkipped = Counter.builder("squish_records_skipped_total")
                .description("Total number of records skipped (non-PDF files)")
                .register(registry);

        this.recordsFailed = Counter.builder("squish_records_failed_total")
                .description("Total number of compression failures")
                .register(registry);

        this.bytesOriginal = Counter.builder("squish_bytes_original_total")
                .description("Total original bytes processed")
                .baseUnit("bytes")
                .register(registry);

        this.bytesCompressed = Counter.builder("squish_bytes_compressed_total")
                .description("Total compressed bytes produced")
                .baseUnit("bytes")
                .register(registry);

        // Initialize gauges
        Gauge.builder("squish_queue_size", queueSize, AtomicLong::get)
                .description("Current size of the compression queue")
                .register(registry);

        Gauge.builder("squish_active_workers", activeWorkers, AtomicLong::get)
                .description("Number of active compression workers")
                .register(registry);

        Gauge.builder("squish_watchdog_cycle", currentCycle, AtomicLong::get)
                .description("Current watchdog cycle number")
                .register(registry);

        // Compression ratio gauge (calculated)
        Gauge.builder("squish_compression_ratio", this, SquishMetrics::getCompressionRatio)
                .description("Current compression ratio (compressed/original)")
                .register(registry);

        // Savings percentage gauge
        Gauge.builder("squish_savings_percent", this, SquishMetrics::getSavingsPercent)
                .description("Current savings percentage")
                .register(registry);

        // Initialize timer
        this.compressionTimer = Timer.builder("squish_compression_duration")
                .description("Time spent compressing PDFs")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        // On-demand REST compression meters
        this.onDemandCalls = Counter.builder("squish_ondemand_calls_total")
                .description("Total number of on-demand REST compression calls")
                .register(registry);

        this.onDemandTimer = Timer.builder("squish_ondemand_duration")
                .description("Time spent on on-demand REST compressions")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        // System metrics
        this.osMxBean = ManagementFactory.getOperatingSystemMXBean();
        this.memoryMxBean = ManagementFactory.getMemoryMXBean();

        // JVM metrics
        Gauge.builder("squish_jvm_cpu_usage", this, SquishMetrics::getCpuUsage)
                .description("JVM CPU usage percentage")
                .register(registry);

        Gauge.builder("squish_jvm_memory_used_bytes", this, SquishMetrics::getHeapUsed)
                .description("JVM heap memory used")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("squish_jvm_memory_max_bytes", this, SquishMetrics::getHeapMax)
                .description("JVM heap memory max")
                .baseUnit("bytes")
                .register(registry);

        Gauge.builder("squish_jvm_threads_active", this, SquishMetrics::getActiveThreads)
                .description("Number of active JVM threads")
                .register(registry);

        log.info("Prometheus metrics initialized");
    }

    public static synchronized SquishMetrics getInstance() {
        if (instance == null) {
            instance = new SquishMetrics();
        }
        return instance;
    }

    // ==================== Recording methods ====================

    public void recordRead() {
        recordsRead.increment();
    }

    public void recordCompressed(long originalBytes, long compressedBytes, long durationMs) {
        recordsCompressed.increment();
        bytesOriginal.increment(originalBytes);
        bytesCompressed.increment(compressedBytes);
        compressionTimer.record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordSkipped() {
        recordsSkipped.increment();
    }

    public void recordFailed() {
        recordsFailed.increment();
    }

    public void setQueueSize(int size) {
        queueSize.set(size);
    }

    public void setActiveWorkers(int count) {
        activeWorkers.set(count);
    }

    public void setCycle(long cycle) {
        currentCycle.set(cycle);
    }

    public void incrementActiveWorkers() {
        activeWorkers.incrementAndGet();
    }

    public void decrementActiveWorkers() {
        activeWorkers.decrementAndGet();
    }

    // ==================== On-demand REST tracking ====================

    /**
     * Record one on-demand REST compression. Safe for concurrent callers:
     * counters are atomic and last-* fields are volatile (last-writer-wins).
     */
    public void recordOnDemand(String filename, double savingsPercent, long durationMs, boolean success) {
        onDemandCount.incrementAndGet();
        onDemandTotalMs.addAndGet(durationMs);
        this.onDemandLastFilename = filename;
        this.onDemandLastSavingsPercent = savingsPercent;
        this.onDemandLastSuccess = success;
        onDemandCalls.increment();
        onDemandTimer.record(java.time.Duration.ofMillis(durationMs));
    }

    public long getOnDemandCalls() {
        return onDemandCount.get();
    }

    public double getOnDemandAvgMs() {
        long calls = onDemandCount.get();
        return calls > 0 ? (double) onDemandTotalMs.get() / calls : 0.0;
    }

    public String getOnDemandLastFilename() {
        return onDemandLastFilename;
    }

    public double getOnDemandLastSavingsPercent() {
        return onDemandLastSavingsPercent;
    }

    public boolean isOnDemandLastSuccess() {
        return onDemandLastSuccess;
    }

    // ==================== Calculated metrics ====================

    private double getCompressionRatio() {
        double original = bytesOriginal.count();
        double compressed = bytesCompressed.count();
        return original > 0 ? compressed / original : 1.0;
    }

    private double getSavingsPercent() {
        return (1.0 - getCompressionRatio()) * 100.0;
    }

    private double getCpuUsage() {
        if (osMxBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            return sunBean.getProcessCpuLoad() * 100.0;
        }
        return osMxBean.getSystemLoadAverage();
    }

    private double getHeapUsed() {
        return memoryMxBean.getHeapMemoryUsage().getUsed();
    }

    private double getHeapMax() {
        return memoryMxBean.getHeapMemoryUsage().getMax();
    }

    private double getActiveThreads() {
        return Thread.activeCount();
    }

    // ==================== Prometheus scrape ====================

    /**
     * Get metrics in Prometheus text format.
     * Called by /metrics endpoint.
     */
    public String scrape() {
        return registry.scrape();
    }

    /**
     * Get the Micrometer registry for advanced usage.
     */
    public MeterRegistry getRegistry() {
        return registry;
    }
}
