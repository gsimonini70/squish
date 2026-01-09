package com.lucsartech.pdf.pipeline;

import com.lucsartech.pdf.compression.CompressionResult;
import com.lucsartech.pdf.compression.PdfCompressor;
import com.lucsartech.pdf.config.PdfCompressorProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.*;

/**
 * Modern compression pipeline using Virtual Threads (Project Loom).
 *
 * Architecture:
 * <pre>
 * Producer (Virtual Thread)
 *     ↓ BlockingQueue&lt;PdfTask&gt;
 * Worker Pool (Virtual Threads with Semaphore)
 *     ↓ BlockingQueue&lt;CompressionResult&gt;
 * Writer Pool (Virtual Threads with Semaphore)
 * </pre>
 */
public final class CompressionPipeline implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CompressionPipeline.class);

    private final PdfCompressorProperties properties;
    private final ProgressTracker tracker;
    private final PdfCompressor compressor;
    private final HikariDataSource dataSource;

    private final BlockingQueue<PdfTask> taskQueue;
    private final BlockingQueue<CompressionResult.Success> resultQueue;

    private final ExecutorService executor;
    private final Semaphore workerSemaphore;
    private final Semaphore writerSemaphore;

    public CompressionPipeline(PdfCompressorProperties properties, ProgressTracker tracker) {
        this.properties = properties;
        this.tracker = tracker;
        this.compressor = new PdfCompressor(properties.getMode());
        this.dataSource = createDataSource();

        var pipeline = properties.getPipeline();
        this.taskQueue = new ArrayBlockingQueue<>(pipeline.getQueueCapacity());
        this.resultQueue = new ArrayBlockingQueue<>(pipeline.getQueueCapacity());

        // Virtual thread executor for I/O bound operations
        this.executor = Executors.newVirtualThreadPerTaskExecutor();

        // Semaphores to limit concurrent work
        this.workerSemaphore = new Semaphore(pipeline.getWorkerThreads());
        this.writerSemaphore = new Semaphore(pipeline.getWorkerThreads());

        log.info("Pipeline initialized with {} virtual threads, mode: {}",
                pipeline.getWorkerThreads(), properties.getMode());
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
     * Calculate initial statistics from database (excluding already processed).
     */
    public void calculateInitialStats() {
        var pipeline = properties.getPipeline();
        var q = properties.getQuery();
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

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, pipeline.getIdFrom());
            if (pipeline.hasUpperBound()) {
                ps.setLong(2, pipeline.getIdTo());
            }

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long count = rs.getLong("cnt");
                    long size = rs.getLong("total_size");
                    tracker.setInitialStats(count, size);

                    log.info("Initial stats: {} records, {} MB",
                            count, String.format("%.2f", size / 1024.0 / 1024.0));
                }
            }
        } catch (Exception e) {
            log.error("Failed to calculate initial stats", e);
        }
    }

    /**
     * Calculate final database size after compression.
     */
    public void calculateFinalStats() {
        var pipeline = properties.getPipeline();
        var q = properties.getQuery();
        String sql = String.format("""
            SELECT NVL(SUM(DBMS_LOB.GETLENGTH(%s)), 0) AS total_size
            FROM %s
            INNER JOIN %s ON %s = %s
            WHERE (%s)
              AND %s IS NOT NULL
              AND %s >= ?
            """,
            q.getDataColumn(),
            q.getMasterTable(),
            q.getDetailTable(), q.getIdColumn(), q.getDetailIdColumn(),
            q.getMasterTableFilter(),
            q.getDataColumn(),
            q.getIdColumn()
        ) + (pipeline.hasUpperBound() ? String.format(" AND %s <= ?", q.getIdColumn()) : "");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, pipeline.getIdFrom());
            if (pipeline.hasUpperBound()) {
                ps.setLong(2, pipeline.getIdTo());
            }

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long size = rs.getLong("total_size");
                    tracker.setFinalDbSize(size);

                    log.info("Final DB size: {} MB", String.format("%.2f", size / 1024.0 / 1024.0));
                }
            }
        } catch (Exception e) {
            log.error("Failed to calculate final stats", e);
        }
    }

    /**
     * Run the complete compression pipeline.
     */
    public void run() throws Exception {
        tracker.markStarted();
        log.info("Starting compression pipeline...");

        var producerFuture = executor.submit(this::runProducer);
        var workersFuture = executor.submit(this::runWorkers);
        var writersFuture = executor.submit(this::runWriters);

        // Wait for producer to finish
        producerFuture.get();
        log.info("Producer completed");

        // Wait for workers to finish
        workersFuture.get();
        log.info("Workers completed");

        // Signal writers to stop and wait
        for (int i = 0; i < properties.getPipeline().getWorkerThreads(); i++) {
            resultQueue.put(new CompressionResult.Success(-1, null, new byte[0], 0, 0, java.time.Duration.ZERO));
        }
        writersFuture.get();
        log.info("Writers completed");

        tracker.markCompleted();
        log.info("Pipeline completed in {}", tracker.elapsedTime());
    }

    private Void runProducer() {
        var pipeline = properties.getPipeline();
        var q = properties.getQuery();
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
        ) + (pipeline.hasUpperBound() ? String.format(" AND %s <= ?", q.getIdColumn()) : "");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

            ps.setFetchSize(pipeline.getFetchSize());
            ps.setLong(1, pipeline.getIdFrom());
            if (pipeline.hasUpperBound()) {
                ps.setLong(2, pipeline.getIdTo());
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(q.getIdColumn());
                    String filename = rs.getString(q.getFilenameColumn());
                    byte[] pdf = rs.getBinaryStream(q.getDataColumn()).readAllBytes();

                    tracker.recordRead();
                    taskQueue.put(new PdfTask.Data(id, filename, pdf));
                }
            }

            // Send poison pills to workers
            for (int i = 0; i < pipeline.getWorkerThreads(); i++) {
                taskQueue.put(PdfTask.Poison.INSTANCE);
            }

            log.debug("Producer: finished reading {} records", tracker.readCount());

        } catch (Exception e) {
            log.error("Producer error", e);
            tracker.recordError(-999, e);
        }

        return null;
    }

    private Void runWorkers() {
        var pipeline = properties.getPipeline();
        var latch = new CountDownLatch(pipeline.getWorkerThreads());

        for (int i = 0; i < pipeline.getWorkerThreads(); i++) {
            executor.submit(() -> {
                try {
                    runWorker();
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return null;
    }

    private void runWorker() {
        var pipeline = properties.getPipeline();
        String threadName = Thread.currentThread().getName();

        try {
            while (true) {
                PdfTask task = taskQueue.take();

                if (task.isPoison()) {
                    log.debug("[{}] Received poison pill, stopping", threadName);
                    break;
                }

                if (task instanceof PdfTask.Data data) {
                    workerSemaphore.acquire();
                    try {
                        log.trace("[{}] Compressing id={} ({})", threadName, data.id(), data.filename());

                        CompressionResult result = compressor.compress(data.id(), data.filename(), data.pdf());
                        tracker.recordResult(result);

                        if (result instanceof CompressionResult.Success success) {
                            resultQueue.put(success);
                        } else if (!properties.isDryRun()) {
                            // Track skipped/failed records inline to avoid re-processing
                            trackNonSuccess(result);
                        }

                        if (pipeline.getThrottleMillis() > 0) {
                            Thread.sleep(pipeline.getThrottleMillis());
                        }

                    } finally {
                        workerSemaphore.release();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("[{}] Worker interrupted", threadName);
        }
    }

    private Void runWriters() {
        var pipeline = properties.getPipeline();
        var latch = new CountDownLatch(pipeline.getWorkerThreads());

        for (int i = 0; i < pipeline.getWorkerThreads(); i++) {
            executor.submit(() -> {
                try {
                    runWriter();
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return null;
    }

    private void runWriter() {
        String threadName = Thread.currentThread().getName();

        // In DRY-RUN mode, just consume results without DB connection
        if (properties.isDryRun()) {
            runDryRunWriter(threadName);
            return;
        }

        var pipeline = properties.getPipeline();
        var q = properties.getQuery();
        String updateSql = String.format("UPDATE %s SET %s = ? WHERE %s = ?",
            q.getDetailTable(), q.getDataColumn(), q.getDetailIdColumn());
        String trackingSql = String.format("""
            INSERT INTO %s (OTT_ID, ORIGINAL_SIZE, COMPRESSED_SIZE, SAVINGS_PERCENT, STATUS, HOSTNAME)
            VALUES (?, ?, ?, ?, 'SUCCESS', ?)
            """, q.getTrackingTable());
        String hostname = getHostname();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement updatePs = conn.prepareStatement(updateSql);
             PreparedStatement trackingPs = conn.prepareStatement(trackingSql)) {

            conn.setAutoCommit(false);
            int batchCount = 0;

            while (true) {
                CompressionResult.Success result = resultQueue.take();

                // Check for poison pill (id = -1)
                if (result.id() == -1) {
                    log.debug("[{}] Writer stopping", threadName);
                    break;
                }

                writerSemaphore.acquire();
                try {
                    tracker.recordUpdate();

                    // Update compressed data
                    updatePs.setBinaryStream(1, new ByteArrayInputStream(result.compressedData()),
                            result.compressedData().length);
                    updatePs.setLong(2, result.id());
                    updatePs.addBatch();

                    // Track in SQUISH_PROCESSED
                    double savingsPercent = 100.0 * (1 - (double) result.compressedSize() / result.originalSize());
                    trackingPs.setLong(1, result.id());
                    trackingPs.setLong(2, result.originalSize());
                    trackingPs.setLong(3, result.compressedSize());
                    trackingPs.setDouble(4, savingsPercent);
                    trackingPs.setString(5, hostname);
                    trackingPs.addBatch();

                    batchCount++;

                    if (batchCount % pipeline.getBatchSize() == 0) {
                        updatePs.executeBatch();
                        trackingPs.executeBatch();
                        conn.commit();
                        log.debug("[{}] Committed batch of {}", threadName, batchCount);
                    }
                } finally {
                    writerSemaphore.release();
                }
            }

            // Final batch commit
            if (batchCount % pipeline.getBatchSize() != 0) {
                updatePs.executeBatch();
                trackingPs.executeBatch();
                conn.commit();
                log.debug("[{}] Final commit, total: {}", threadName, batchCount);
            }

        } catch (Exception e) {
            log.error("[{}] Writer error", threadName, e);
            tracker.recordError(-997, e);
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
     * Track non-success results (Skipped/Failure) in tracking table to avoid re-processing.
     */
    private void trackNonSuccess(CompressionResult result) {
        var q = properties.getQuery();
        String hostname = getHostname();

        String sql;
        if (result instanceof CompressionResult.Skipped skipped) {
            sql = String.format("""
                INSERT INTO %s (OTT_ID, ORIGINAL_SIZE, STATUS, ERROR_MESSAGE, HOSTNAME)
                VALUES (?, ?, 'SKIPPED', ?, ?)
                """, q.getTrackingTable());

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, skipped.id());
                ps.setLong(2, skipped.size());
                ps.setString(3, skipped.reason());
                ps.setString(4, hostname);
                ps.executeUpdate();
                conn.commit();
            } catch (Exception e) {
                log.warn("Failed to track skipped ID {}: {}", skipped.id(), e.getMessage());
            }
        } else if (result instanceof CompressionResult.Failure failure) {
            sql = String.format("""
                INSERT INTO %s (OTT_ID, ORIGINAL_SIZE, STATUS, ERROR_MESSAGE, HOSTNAME)
                VALUES (?, 0, 'ERROR', ?, ?)
                """, q.getTrackingTable());

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, failure.id());
                ps.setString(2, truncate(failure.errorMessage(), 500));
                ps.setString(3, hostname);
                ps.executeUpdate();
                conn.commit();
            } catch (Exception e) {
                log.warn("Failed to track error ID {}: {}", failure.id(), e.getMessage());
            }
        }
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private void runDryRunWriter(String threadName) {
        try {
            while (true) {
                CompressionResult.Success result = resultQueue.take();

                if (result.id() == -1) {
                    log.debug("[{}] DRY-RUN writer stopping", threadName);
                    break;
                }

                writerSemaphore.acquire();
                try {
                    tracker.recordUpdate();
                    log.trace("[{}] DRY-RUN: would update id={}", threadName, result.id());
                } finally {
                    writerSemaphore.release();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("[{}] DRY-RUN writer interrupted", threadName);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }

        log.info("Pipeline resources released");
    }
}
