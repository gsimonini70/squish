package com.lucsartech.pdf.pipeline;

import com.lucsartech.pdf.compression.PdfCompressor;
import com.lucsartech.pdf.compression.CompressionResult;
import com.lucsartech.pdf.config.PdfCompressorProperties;
import com.lucsartech.pdf.email.EmailService;
import com.lucsartech.pdf.report.ReportGenerator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Watchdog service for continuous PDF compression monitoring.
 * Polls the database for new records and processes them automatically.
 */
public final class WatchdogService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WatchdogService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final PdfCompressorProperties properties;
    private final ProgressTracker tracker;
    private final PdfCompressor compressor;
    private final HikariDataSource dataSource;
    private final EmailService emailService;

    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final Semaphore workerSemaphore;

    private final AtomicLong lastProcessedId;
    private final AtomicLong cycleCount = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Instant lastCycleTime;
    private volatile int lastCycleRecords;

    // Cycle-specific tracking for email reports
    private final AtomicLong cycleOriginalBytes = new AtomicLong(0);
    private final AtomicLong cycleCompressedBytes = new AtomicLong(0);

    @Autowired(required = false)
    private EmailService injectedEmailService;

    public WatchdogService(PdfCompressorProperties properties, ProgressTracker tracker) {
        this.properties = properties;
        this.tracker = tracker;
        this.compressor = new PdfCompressor(properties.getMode());
        this.dataSource = createDataSource();

        // Email service will be set via @Autowired if available
        this.emailService = properties.getEmail().isEnabled()
                ? new EmailService(properties.getEmail())
                : null;

        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watchdog-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.workerSemaphore = new Semaphore(properties.getPipeline().getWorkerThreads());

        // Initialize with the starting ID from config
        this.lastProcessedId = new AtomicLong(properties.getPipeline().getIdFrom());

        log.info("Watchdog initialized - polling every {} seconds, starting from ID {}{}",
                properties.getWatchdog().getPollIntervalSeconds(), lastProcessedId.get(),
                emailService != null ? ", email notifications enabled" : "");
    }

    private HikariDataSource createDataSource() {
        var dbConfig = properties.getDatabase();
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(dbConfig.getJdbcUrl());
        hikariConfig.setUsername(dbConfig.getUsername());
        hikariConfig.setPassword(dbConfig.getPassword());
        hikariConfig.setMaximumPoolSize(dbConfig.getMaxPoolSize());
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setAutoCommit(false);
        hikariConfig.setDriverClassName("oracle.jdbc.OracleDriver");
        hikariConfig.setConnectionTimeout(30000);
        hikariConfig.setIdleTimeout(600000);
        hikariConfig.setMaxLifetime(1800000);
        return new HikariDataSource(hikariConfig);
    }

    /**
     * Calculate initial database statistics for progress tracking (excluding already processed).
     */
    public void calculateInitialStats() {
        long startId = properties.getPipeline().getIdFrom();
        String sql = """
            SELECT COUNT(*) AS cnt, NVL(SUM(DBMS_LOB.GETLENGTH(OTTI_DATA)), 0) AS total_size
            FROM OTTICA
            INNER JOIN OTTICAI ON OTT_ID = OTTI_ID
            WHERE OTT_TIPO_DOC = '001030'
              AND OTTI_DATA IS NOT NULL
              AND OTT_ID > ?
              AND NOT EXISTS (SELECT 1 FROM SQUISH_PROCESSED SP WHERE SP.OTT_ID = OTTICA.OTT_ID)
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, startId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long count = rs.getLong("cnt");
                    long totalSize = rs.getLong("total_size");
                    tracker.setInitialStats(count, totalSize);
                    log.info("Initial stats: {} records, {} MB from ID > {}",
                            count, String.format("%.2f", totalSize / 1024.0 / 1024.0), startId);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to calculate initial stats", e);
        }
    }

    /**
     * Start the watchdog service.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            tracker.markStarted();

            // Calculate initial stats for progress tracking
            log.info("Calculating initial database statistics...");
            calculateInitialStats();

            // Run initial cycle immediately
            log.info("Starting watchdog service...");
            scheduler.execute(this::runCycle);

            // Schedule periodic cycles
            scheduler.scheduleAtFixedRate(
                    this::runCycle,
                    properties.getWatchdog().getPollIntervalSeconds(),
                    properties.getWatchdog().getPollIntervalSeconds(),
                    TimeUnit.SECONDS
            );

            log.info("Watchdog started - press Ctrl+C to stop");
        }
    }

    /**
     * Run a single compression cycle.
     */
    private void runCycle() {
        long cycle = cycleCount.incrementAndGet();
        String timeStr = LocalDateTime.now().format(TIME_FMT);

        // Reset cycle-specific counters
        cycleOriginalBytes.set(0);
        cycleCompressedBytes.set(0);

        log.info("=== Cycle #{} started at {} (last ID: {}) ===",
                cycle, timeStr, lastProcessedId.get());

        try {
            int processed = processNewRecords();
            lastCycleTime = Instant.now();
            lastCycleRecords = processed;

            if (processed > 0) {
                log.info("Cycle #{} completed: {} records processed, last ID: {}",
                        cycle, processed, lastProcessedId.get());

                // Generate report and send email if configured
                if (emailService != null) {
                    sendCycleReport(cycle, processed);
                }
            } else {
                log.info("Cycle #{} completed: no new records found", cycle);
            }

        } catch (Exception e) {
            log.error("Cycle #{} failed", cycle, e);
        }
    }

    /**
     * Generate and send cycle report via email.
     */
    private void sendCycleReport(long cycle, int recordsProcessed) {
        try {
            // Generate PDF report for this cycle
            String reportName = String.format("watchdog_cycle_%d_report", cycle);
            Path reportPath = ReportGenerator.generate(tracker, properties, reportName);
            log.info("Cycle report generated: {}", reportPath);

            // Calculate cycle-specific metrics
            double originalMb = cycleOriginalBytes.get() / 1024.0 / 1024.0;
            double compressedMb = cycleCompressedBytes.get() / 1024.0 / 1024.0;
            double savingsPercent = originalMb > 0
                    ? (1.0 - compressedMb / originalMb) * 100.0
                    : 0.0;

            // Send email
            emailService.sendCycleReport(cycle, recordsProcessed, originalMb,
                    compressedMb, savingsPercent, reportPath, properties.isDryRun());

        } catch (Exception e) {
            log.error("Failed to send cycle #{} report", cycle, e);
        }
    }

    /**
     * Process all new records since last processed ID (excluding already processed).
     */
    private int processNewRecords() {
        long startId = lastProcessedId.get();

        // Query for new records (excluding already processed)
        String sql = """
            SELECT OTT_ID, OTT_NOME_FILE, OTTI_DATA
            FROM OTTICA
            INNER JOIN OTTICAI ON OTT_ID = OTTI_ID
            WHERE OTT_TIPO_DOC = '001030'
              AND OTTI_DATA IS NOT NULL
              AND OTT_ID > ?
              AND NOT EXISTS (SELECT 1 FROM SQUISH_PROCESSED SP WHERE SP.OTT_ID = OTTICA.OTT_ID)
            ORDER BY OTT_ID
            """;

        int processedCount = 0;
        BlockingQueue<CompletableFuture<Void>> futures = new LinkedBlockingQueue<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql,
                     ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

            ps.setFetchSize(properties.getPipeline().getFetchSize());
            ps.setLong(1, startId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("OTT_ID");
                    String filename = rs.getString("OTT_NOME_FILE");
                    byte[] pdfData = rs.getBinaryStream("OTTI_DATA").readAllBytes();

                    tracker.recordRead();

                    // Process asynchronously
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            workerSemaphore.acquire();
                            try {
                                processRecord(id, filename, pdfData);
                            } finally {
                                workerSemaphore.release();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }, executor);

                    futures.add(future);
                    processedCount++;

                    // Update last processed ID
                    lastProcessedId.updateAndGet(current -> Math.max(current, id));

                    // Throttle if configured
                    if (properties.getPipeline().getThrottleMillis() > 0) {
                        Thread.sleep(properties.getPipeline().getThrottleMillis());
                    }
                }
            }

            // Wait for all async operations to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        } catch (Exception e) {
            log.error("Error processing new records", e);
            tracker.recordError(-1, e);
        }

        return processedCount;
    }

    /**
     * Process a single record.
     */
    private void processRecord(long id, String filename, byte[] pdfData) {
        CompressionResult result = compressor.compress(id, filename, pdfData);
        tracker.recordResult(result);

        if (result instanceof CompressionResult.Success success) {
            // Track cycle-specific bytes for email reports
            cycleOriginalBytes.addAndGet(success.originalSize());
            cycleCompressedBytes.addAndGet(success.compressedSize());

            if (!properties.isDryRun()) {
                updateDatabase(success);
            } else {
                log.debug("DRY-RUN: would update ID {} ({} -> {} bytes)",
                        id, success.originalSize(), success.compressedSize());
            }
            tracker.recordUpdate();
        }
    }

    /**
     * Update the database with compressed data and track in SQUISH_PROCESSED.
     */
    private void updateDatabase(CompressionResult.Success result) {
        String updateSql = "UPDATE OTTICAI SET OTTI_DATA = ? WHERE OTTI_ID = ?";
        String trackingSql = """
            INSERT INTO SQUISH_PROCESSED (OTT_ID, ORIGINAL_SIZE, COMPRESSED_SIZE, SAVINGS_PERCENT, STATUS, HOSTNAME)
            VALUES (?, ?, ?, ?, 'SUCCESS', ?)
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement updatePs = conn.prepareStatement(updateSql);
             PreparedStatement trackingPs = conn.prepareStatement(trackingSql)) {

            // Update compressed data
            updatePs.setBinaryStream(1,
                    new ByteArrayInputStream(result.compressedData()),
                    result.compressedData().length);
            updatePs.setLong(2, result.id());
            updatePs.executeUpdate();

            // Track in SQUISH_PROCESSED
            double savingsPercent = 100.0 * (1 - (double) result.compressedSize() / result.originalSize());
            trackingPs.setLong(1, result.id());
            trackingPs.setLong(2, result.originalSize());
            trackingPs.setLong(3, result.compressedSize());
            trackingPs.setDouble(4, savingsPercent);
            trackingPs.setString(5, getHostname());
            trackingPs.executeUpdate();

            conn.commit();

        } catch (Exception e) {
            log.error("Failed to update ID {}", result.id(), e);
            tracker.recordError(result.id(), e);
        }
    }

    private String getHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Get watchdog status for monitoring.
     */
    public WatchdogStatus getStatus() {
        return new WatchdogStatus(
                running.get(),
                cycleCount.get(),
                lastProcessedId.get(),
                lastCycleTime,
                lastCycleRecords,
                properties.getWatchdog().getPollIntervalSeconds()
        );
    }

    /**
     * Watchdog status record.
     */
    public record WatchdogStatus(
            boolean running,
            long cycleCount,
            long lastProcessedId,
            Instant lastCycleTime,
            int lastCycleRecords,
            int pollIntervalSeconds
    ) {
        public String nextCycleIn() {
            if (lastCycleTime == null) return "now";
            long elapsed = Duration.between(lastCycleTime, Instant.now()).toSeconds();
            long remaining = Math.max(0, pollIntervalSeconds - elapsed);
            return remaining + "s";
        }
    }

    @Override
    public void close() {
        running.set(false);

        scheduler.shutdown();
        executor.shutdown();

        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }

        tracker.markCompleted();
        log.info("Watchdog service stopped. Total cycles: {}, Last ID: {}",
                cycleCount.get(), lastProcessedId.get());
    }
}
