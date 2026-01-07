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
     * Calculate initial statistics from database.
     */
    public void calculateInitialStats() {
        var pipeline = properties.getPipeline();
        String sql = """
            SELECT COUNT(*) AS cnt, NVL(SUM(DBMS_LOB.GETLENGTH(OTTI_DATA)), 0) AS total_size
            FROM OTTICA
            INNER JOIN OTTICAI ON OTT_ID = OTTI_ID
            WHERE OTT_TIPO_DOC = '001030'
              AND OTTI_DATA IS NOT NULL
              AND OTT_ID >= ?
            """ + (pipeline.hasUpperBound() ? " AND OTT_ID <= ?" : "");

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
        String sql = """
            SELECT NVL(SUM(DBMS_LOB.GETLENGTH(OTTI_DATA)), 0) AS total_size
            FROM OTTICA
            INNER JOIN OTTICAI ON OTT_ID = OTTI_ID
            WHERE OTT_TIPO_DOC = '001030'
              AND OTTI_DATA IS NOT NULL
              AND OTT_ID >= ?
            """ + (pipeline.hasUpperBound() ? " AND OTT_ID <= ?" : "");

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
        String sql = """
            SELECT OTT_ID, OTT_NOME_FILE, OTTI_DATA
            FROM OTTICA
            INNER JOIN OTTICAI ON OTT_ID = OTTI_ID
            WHERE OTT_TIPO_DOC = '001030'
              AND OTTI_DATA IS NOT NULL
              AND OTT_ID >= ?
            """ + (pipeline.hasUpperBound() ? " AND OTT_ID <= ?" : "");

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
                    long id = rs.getLong("OTT_ID");
                    String filename = rs.getString("OTT_NOME_FILE");
                    byte[] pdf = rs.getBinaryStream("OTTI_DATA").readAllBytes();

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
        String updateSql = "UPDATE OTTICAI SET OTTI_DATA = ? WHERE OTTI_ID = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(updateSql)) {

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
                    ps.setBinaryStream(1, new ByteArrayInputStream(result.compressedData()),
                            result.compressedData().length);
                    ps.setLong(2, result.id());
                    ps.addBatch();
                    batchCount++;

                    if (batchCount % pipeline.getBatchSize() == 0) {
                        ps.executeBatch();
                        conn.commit();
                        log.debug("[{}] Committed batch of {}", threadName, batchCount);
                    }
                } finally {
                    writerSemaphore.release();
                }
            }

            // Final batch commit
            if (batchCount % pipeline.getBatchSize() != 0) {
                ps.executeBatch();
                conn.commit();
                log.debug("[{}] Final commit, total: {}", threadName, batchCount);
            }

        } catch (Exception e) {
            log.error("[{}] Writer error", threadName, e);
            tracker.recordError(-997, e);
        }
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
