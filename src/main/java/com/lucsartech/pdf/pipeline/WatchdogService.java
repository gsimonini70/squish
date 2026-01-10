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
        var pipeline = properties.getPipeline();
        var q = properties.getQuery();
        long startId = pipeline.getIdFrom();
        String sql = String.format("""
            SELECT COUNT(*) AS cnt, NVL(SUM(DBMS_LOB.GETLENGTH(%s)), 0) AS total_size
            FROM %s
            INNER JOIN %s ON %s = %s
            WHERE (%s)
              AND %s IS NOT NULL
              AND %s >= ?
              AND NOT EXISTS (SELECT 1 FROM %s SP WHERE SP.OTT_ID = %s.%s)
            """,
            q.getDataColumn(),
            q.getMasterTable(),
            q.getDetailTable(), q.getIdColumn(), q.getDetailIdColumn(),
            q.getMasterTableFilter(),
            q.getDataColumn(),
            q.getIdColumn(),
            q.getTrackingTable(), q.getMasterTable(), q.getIdColumn()
        ) + (pipeline.hasUpperBound() ? String.format(" AND %s <= ?", q.getIdColumn()) : "");

        log.debug("Initial stats SQL: {}", sql);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, startId);
            if (pipeline.hasUpperBound()) {
                ps.setLong(2, pipeline.getIdTo());
            }

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
            log.error("Failed to calculate initial stats. Filter: [{}], SQL: {}",
                    q.getMasterTableFilter(), sql, e);
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
            String reportName = String.format("squish_cycle_%d", cycle);
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
     * Process all new records within configured ID range (excluding already processed).
     */
    private int processNewRecords() {
        var pipeline = properties.getPipeline();
        var q = properties.getQuery();
        long startId = Math.max(lastProcessedId.get(), pipeline.getIdFrom());

        // Query for new records (excluding already processed, respecting ID range)
        String sql = String.format("""
            SELECT %s, %s, %s
            FROM %s
            INNER JOIN %s ON %s = %s
            WHERE (%s)
              AND %s IS NOT NULL
              AND %s >= ?
              AND NOT EXISTS (SELECT 1 FROM %s SP WHERE SP.OTT_ID = %s.%s)
            """,
            q.getIdColumn(), q.getFilenameColumn(), q.getDataColumn(),
            q.getMasterTable(),
            q.getDetailTable(), q.getIdColumn(), q.getDetailIdColumn(),
            q.getMasterTableFilter(),
            q.getDataColumn(),
            q.getIdColumn(),
            q.getTrackingTable(), q.getMasterTable(), q.getIdColumn()
        ) + (pipeline.hasUpperBound() ? String.format(" AND %s <= ?", q.getIdColumn()) : "")
          + String.format(" ORDER BY %s", q.getIdColumn());

        int processedCount = 0;
        BlockingQueue<CompletableFuture<Void>> futures = new LinkedBlockingQueue<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql,
                     ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

            ps.setFetchSize(pipeline.getFetchSize());
            ps.setLong(1, startId);
            if (pipeline.hasUpperBound()) {
                ps.setLong(2, pipeline.getIdTo());
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(q.getIdColumn());
                    String filename = rs.getString(q.getFilenameColumn());
                    byte[] pdfData = rs.getBinaryStream(q.getDataColumn()).readAllBytes();

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
        } else if (result instanceof CompressionResult.Skipped skipped) {
            // Track skipped files to avoid re-processing
            if (!properties.isDryRun()) {
                trackSkipped(skipped);
            } else {
                log.debug("DRY-RUN: would track skipped ID {} ({})", id, skipped.reason());
            }
        } else if (result instanceof CompressionResult.Failure failure) {
            // Track failed files to avoid re-processing
            if (!properties.isDryRun()) {
                trackFailure(failure);
            } else {
                log.debug("DRY-RUN: would track failed ID {} ({})", id, failure.errorMessage());
            }
        }
    }

    /**
     * Update the database with compressed data and track in tracking table.
     */
    private void updateDatabase(CompressionResult.Success result) {
        var q = properties.getQuery();
        String updateSql = String.format("UPDATE %s SET %s = ? WHERE %s = ?",
                q.getDetailTable(), q.getDataColumn(), q.getDetailIdColumn());
        // Use MERGE to handle duplicate records (upsert)
        String trackingSql = String.format("""
            MERGE INTO %s T
            USING (SELECT ? AS OTT_ID, ? AS ORIG_SIZE, ? AS COMP_SIZE, ? AS SAVINGS, ? AS HOST FROM DUAL) S
            ON (T.OTT_ID = S.OTT_ID)
            WHEN MATCHED THEN UPDATE SET
                T.ORIGINAL_SIZE = S.ORIG_SIZE, T.COMPRESSED_SIZE = S.COMP_SIZE,
                T.SAVINGS_PERCENT = S.SAVINGS, T.STATUS = 'SUCCESS',
                T.HOSTNAME = S.HOST, T.PROCESSED_DATE = SYSTIMESTAMP
            WHEN NOT MATCHED THEN INSERT (OTT_ID, ORIGINAL_SIZE, COMPRESSED_SIZE, SAVINGS_PERCENT, STATUS, HOSTNAME)
                VALUES (S.OTT_ID, S.ORIG_SIZE, S.COMP_SIZE, S.SAVINGS, 'SUCCESS', S.HOST)
            """, q.getTrackingTable());

        try (Connection conn = dataSource.getConnection();
             PreparedStatement updatePs = conn.prepareStatement(updateSql);
             PreparedStatement trackingPs = conn.prepareStatement(trackingSql)) {

            // Update compressed data
            updatePs.setBinaryStream(1,
                    new ByteArrayInputStream(result.compressedData()),
                    result.compressedData().length);
            updatePs.setLong(2, result.id());
            updatePs.executeUpdate();

            // Track in SQUISH_PROCESSED (upsert)
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

    /**
     * Track a skipped (non-PDF) record in the tracking table.
     */
    private void trackSkipped(CompressionResult.Skipped result) {
        var q = properties.getQuery();
        // Use MERGE to handle duplicate records (upsert)
        String trackingSql = String.format("""
            MERGE INTO %s T
            USING (SELECT ? AS OTT_ID, ? AS ORIG_SIZE, ? AS ERR_MSG, ? AS HOST FROM DUAL) S
            ON (T.OTT_ID = S.OTT_ID)
            WHEN MATCHED THEN UPDATE SET
                T.ORIGINAL_SIZE = S.ORIG_SIZE, T.STATUS = 'SKIPPED',
                T.ERROR_MESSAGE = S.ERR_MSG, T.HOSTNAME = S.HOST, T.PROCESSED_DATE = SYSTIMESTAMP
            WHEN NOT MATCHED THEN INSERT (OTT_ID, ORIGINAL_SIZE, STATUS, ERROR_MESSAGE, HOSTNAME)
                VALUES (S.OTT_ID, S.ORIG_SIZE, 'SKIPPED', S.ERR_MSG, S.HOST)
            """, q.getTrackingTable());

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(trackingSql)) {

            ps.setLong(1, result.id());
            ps.setLong(2, result.size());
            ps.setString(3, result.reason());
            ps.setString(4, getHostname());
            ps.executeUpdate();
            conn.commit();

        } catch (Exception e) {
            log.error("Failed to track skipped ID {}", result.id(), e);
        }
    }

    /**
     * Track a failed record in the tracking table.
     */
    private void trackFailure(CompressionResult.Failure result) {
        var q = properties.getQuery();
        // Use MERGE to handle duplicate records (upsert)
        String trackingSql = String.format("""
            MERGE INTO %s T
            USING (SELECT ? AS OTT_ID, ? AS ERR_MSG, ? AS HOST FROM DUAL) S
            ON (T.OTT_ID = S.OTT_ID)
            WHEN MATCHED THEN UPDATE SET
                T.ORIGINAL_SIZE = 0, T.STATUS = 'ERROR',
                T.ERROR_MESSAGE = S.ERR_MSG, T.HOSTNAME = S.HOST, T.PROCESSED_DATE = SYSTIMESTAMP
            WHEN NOT MATCHED THEN INSERT (OTT_ID, ORIGINAL_SIZE, STATUS, ERROR_MESSAGE, HOSTNAME)
                VALUES (S.OTT_ID, 0, 'ERROR', S.ERR_MSG, S.HOST)
            """, q.getTrackingTable());

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(trackingSql)) {

            ps.setLong(1, result.id());
            ps.setString(2, truncate(result.errorMessage(), 500));
            ps.setString(3, getHostname());
            ps.executeUpdate();
            conn.commit();

        } catch (Exception e) {
            log.error("Failed to track error ID {}", result.id(), e);
        }
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) : s;
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
