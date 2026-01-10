package com.lucsartech.pdf.metrics;

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

    // System metrics
    private final OperatingSystemMXBean osMxBean;
    private final MemoryMXBean memoryMxBean;

    private SquishMetrics() {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        // Add common tags
        registry.config().commonTags(
            "application", "squish",
            "version", "3.0.0"
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
