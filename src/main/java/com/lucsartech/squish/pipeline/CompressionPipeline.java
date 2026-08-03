package com.lucsartech.squish.pipeline;

import com.lucsartech.squish.compression.CompressionResult;
import com.lucsartech.squish.compression.Squish;
import com.lucsartech.squish.config.SquishProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
 *
 * <p>Both hand-offs go through {@link Handoff}, which makes a dead consuming stage a loud,
 * bounded failure instead of an eternal block on a full queue - see that class for details.
 */
public final class CompressionPipeline implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CompressionPipeline.class);

    /**
     * How long a producing stage waits on a full queue before re-checking whether the
     * consuming stage is still alive. Only a liveness poll interval: it does not cap
     * how long a healthy stage may wait for a slow consumer.
     */
    static final long QUEUE_OFFER_POLL_MILLIS = 500;

    private final SquishProperties properties;
    private final ProgressTracker tracker;
    private final Squish compressor;
    private final HikariDataSource dataSource;

    private final Handoff<PdfTask> taskHandoff;
    private final Handoff<CompressionResult.Success> resultHandoff;

    private final ExecutorService executor;
    private final Semaphore workerSemaphore;
    private final Semaphore writerSemaphore;

    /** Shared by every writer: the "driver does not report row counts" warning is issued once per run. */
    private final AtomicBoolean rowCountWarningIssued = new AtomicBoolean();

    public CompressionPipeline(SquishProperties properties, ProgressTracker tracker) {
        this.properties = properties;
        this.tracker = tracker;
        this.compressor = new Squish(properties.getActiveCompressionProfile());
        this.dataSource = createDataSource();

        var pipeline = properties.getPipeline();
        // Consumer counts are known up front (one consumer per worker thread), so the
        // hand-offs can tell "no consumer has started yet" from "every consumer is gone".
        this.taskHandoff = new Handoff<>("workers", pipeline.getQueueCapacity(),
                pipeline.getWorkerThreads(), QUEUE_OFFER_POLL_MILLIS);
        this.resultHandoff = new Handoff<>("writers", pipeline.getQueueCapacity(),
                pipeline.getWorkerThreads(), QUEUE_OFFER_POLL_MILLIS);

        // Virtual thread executor for I/O bound operations
        this.executor = Executors.newVirtualThreadPerTaskExecutor();

        // Semaphores to limit concurrent work
        this.workerSemaphore = new Semaphore(pipeline.getWorkerThreads());
        this.writerSemaphore = new Semaphore(pipeline.getWorkerThreads());

        log.info("Pipeline initialized with {} virtual threads, profile: {}",
                pipeline.getWorkerThreads(), properties.getActiveCompressionProfile());
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
              AND NOT EXISTS (SELECT 1 FROM %s SP
                               WHERE SP.OTT_ID = %s.%s
                                 AND SP.OTT_CTR = %s.%s)
            """,
            q.getDataColumn(),
            q.getMasterTable(),
            q.getDetailTable(), q.getIdColumn(), q.getDetailIdColumn(),
            q.getMasterTableFilter(),
            q.getDataColumn(),
            q.getIdColumn(),
            q.getTrackingTable(),
            q.getDetailTable(), q.getDetailIdColumn(),
            q.getDetailTable(), q.getDetailCtrColumn()
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
     * The writer shutdown sentinel: a {@link CompressionResult.Success} with {@code id == -1}.
     * Writers stop when they take a result whose id is -1, so -1 is not a usable record ID.
     */
    static CompressionResult.Success writerSentinel() {
        return new CompressionResult.Success(-1, 0, null, new byte[0], 0, 0, Duration.ZERO);
    }

    /**
     * Run the complete compression pipeline.
     *
     * @throws PipelineFailedException if a worker or writer thread died; the run is aborted
     *         rather than left hanging on a queue nobody drains.
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

        // Signal writers to stop and wait. putSentinel() retries only while a writer is still
        // alive, so if the writer stage is already dead this cannot block.
        for (int i = 0; i < properties.getPipeline().getWorkerThreads(); i++) {
            resultHandoff.putSentinel(writerSentinel());
        }
        writersFuture.get();
        log.info("Writers completed");

        Throwable fatal = firstStageFailure();
        if (fatal != null) {
            // Nothing will ever consume what is left in the queues; release it.
            taskHandoff.discardPending();
            resultHandoff.discardPending();
            throw new PipelineFailedException(
                    "Compression pipeline aborted: a pipeline stage died (" + fatal + ")", fatal);
        }

        tracker.markCompleted();
        log.info("Pipeline completed in {}", tracker.elapsedTime());
    }

    /** First fatal failure of any consuming stage, or null if every stage ended cleanly. */
    private Throwable firstStageFailure() {
        Throwable writerFailure = resultHandoff.fatalFailure();
        return writerFailure != null ? writerFailure : taskHandoff.fatalFailure();
    }

    private Void runProducer() {
        var pipeline = properties.getPipeline();
        var q = properties.getQuery();
        // Include CTR column for composite PK (OTTI_ID, OTTI_CTR)
        String sql = String.format("""
            SELECT %s, %s, %s, %s
            FROM %s
            INNER JOIN %s ON %s = %s
            WHERE (%s)
              AND %s IS NOT NULL
              AND %s >= ?
              AND NOT EXISTS (SELECT 1 FROM %s SP
                               WHERE SP.OTT_ID = %s.%s
                                 AND SP.OTT_CTR = %s.%s)
            """,
            q.getIdColumn(), q.getDetailCtrColumn(), q.getFilenameColumn(), q.getDataColumn(),
            q.getMasterTable(),
            q.getDetailTable(), q.getIdColumn(), q.getDetailIdColumn(),
            q.getMasterTableFilter(),
            q.getDataColumn(),
            q.getIdColumn(),
            q.getTrackingTable(),
            q.getDetailTable(), q.getDetailIdColumn(),
            q.getDetailTable(), q.getDetailCtrColumn()
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
                    long ctr = rs.getLong(q.getDetailCtrColumn());
                    String filename = rs.getString(q.getFilenameColumn());
                    byte[] pdf = rs.getBinaryStream(q.getDataColumn()).readAllBytes();

                    tracker.recordRead();
                    taskHandoff.put(new PdfTask.Data(id, ctr, filename, pdf));
                }
            }

            log.debug("Producer: finished reading {} records", tracker.readCount());

        } catch (PipelineAbortedException e) {
            // The worker stage is gone - stop reading instead of blocking on a queue nobody drains.
            log.error("Producer aborted: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Producer error", e);
            tracker.recordError(-999, e);
        } finally {
            // Poison pills go out on EVERY exit path, including failure: otherwise workers would
            // block forever in take() and the run would never terminate.
            try {
                for (int i = 0; i < pipeline.getWorkerThreads(); i++) {
                    taskHandoff.putSentinel(PdfTask.Poison.INSTANCE);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
                    taskHandoff.consumerFinished();
                } catch (PipelineAbortedException e) {
                    // Victim, not cause: the writer stage died. Deregister so the producer
                    // stops too once no worker is left, but do not mask the writer's failure.
                    log.warn("[{}] Worker stopping: {}", Thread.currentThread().getName(), e.getMessage());
                    taskHandoff.consumerFinished();
                } catch (Throwable t) {
                    if (t instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.error("[{}] Worker died", Thread.currentThread().getName(), t);
                    tracker.recordError(-998, t);
                    taskHandoff.consumerFailed(t);
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

    private void runWorker() throws InterruptedException {
        var pipeline = properties.getPipeline();
        String threadName = Thread.currentThread().getName();

        while (true) {
            PdfTask task = taskHandoff.take();

            if (task.isPoison()) {
                log.debug("[{}] Received poison pill, stopping", threadName);
                break;
            }

            if (task instanceof PdfTask.Data data) {
                workerSemaphore.acquire();
                try {
                    log.trace("[{}] Compressing id={} ({})", threadName, data.id(), data.filename());

                    CompressionResult result = compressor.compress(data.id(), data.ctr(), data.filename(), data.pdf());
                    tracker.recordResult(result);

                    if (result instanceof CompressionResult.Success success) {
                        // Throws PipelineAbortedException instead of blocking forever if the
                        // writer stage is dead and nothing is draining the result queue.
                        resultHandoff.put(success);
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
    }

    private Void runWriters() {
        var pipeline = properties.getPipeline();
        var latch = new CountDownLatch(pipeline.getWorkerThreads());

        for (int i = 0; i < pipeline.getWorkerThreads(); i++) {
            executor.submit(() -> {
                try {
                    runWriter();
                    resultHandoff.consumerFinished();
                } catch (Throwable t) {
                    // A writer that dies stops draining the result queue. Record it as fatal so
                    // blocked workers/producer unblock and run() fails loudly instead of hanging.
                    if (t instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.error("[{}] Writer died", Thread.currentThread().getName(), t);
                    tracker.recordError(-997, t);
                    resultHandoff.consumerFailed(t);
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

    private void runWriter() throws Exception {
        String threadName = Thread.currentThread().getName();

        // In DRY-RUN mode, just consume results without DB connection
        if (properties.isDryRun()) {
            runDryRunWriter(threadName);
            return;
        }

        var pipeline = properties.getPipeline();
        var q = properties.getQuery();
        String updateSql = String.format("UPDATE %s SET %s = ? WHERE %s = ? AND %s = ?",
            q.getDetailTable(), q.getDataColumn(), q.getDetailIdColumn(), q.getDetailCtrColumn());
        // MERGE, not INSERT: the tracking row may already exist from an earlier run. It is keyed on
        // (OTT_ID, OTT_CTR) - one row per detail part - so parts of the same document no longer
        // overwrite each other. OTT_ID/OTT_CTR are hardcoded here, as everywhere on the tracking side.
        String trackingSql = String.format("""
            MERGE INTO %s T
            USING (SELECT ? AS OTT_ID, ? AS OTT_CTR, ? AS ORIG_SIZE, ? AS COMP_SIZE, ? AS SAVINGS, ? AS HOST FROM DUAL) S
            ON (T.OTT_ID = S.OTT_ID AND T.OTT_CTR = S.OTT_CTR)
            WHEN MATCHED THEN UPDATE SET
                T.ORIGINAL_SIZE = S.ORIG_SIZE, T.COMPRESSED_SIZE = S.COMP_SIZE,
                T.SAVINGS_PERCENT = S.SAVINGS, T.STATUS = 'SUCCESS',
                T.HOSTNAME = S.HOST, T.PROCESSED_DATE = SYSTIMESTAMP
            WHEN NOT MATCHED THEN INSERT (OTT_ID, OTT_CTR, ORIGINAL_SIZE, COMPRESSED_SIZE, SAVINGS_PERCENT, STATUS, HOSTNAME)
                VALUES (S.OTT_ID, S.OTT_CTR, S.ORIG_SIZE, S.COMP_SIZE, S.SAVINGS, 'SUCCESS', S.HOST)
            """, q.getTrackingTable());
        String hostname = getHostname();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement updatePs = conn.prepareStatement(updateSql);
             PreparedStatement trackingPs = conn.prepareStatement(trackingSql)) {

            conn.setAutoCommit(false);
            // The records of the batch currently pending on updatePs/trackingPs, in addBatch()
            // order: executeBatch() returns its row counts in that same order, so this is what
            // maps a zero row count back to the record that produced it.
            List<CompressionResult.Success> pending = new ArrayList<>();
            int totalCount = 0;

            while (true) {
                CompressionResult.Success result = resultHandoff.take();

                // Check for poison pill (id = -1)
                if (result.id() == -1) {
                    log.debug("[{}] Writer stopping", threadName);
                    break;
                }

                writerSemaphore.acquire();
                try {
                    // Deliberately NOT tracker.recordUpdate() here: a record queued into the batch is
                    // not a record written. The batch can still be rolled back, and the replay can
                    // still drop it. The counter is bumped only where a commit actually succeeds.
                    bindUpdate(updatePs, result);
                    updatePs.addBatch();

                    bindSuccessTracking(trackingPs, result, hostname);
                    trackingPs.addBatch();

                    pending.add(result);
                    totalCount++;

                    if (pending.size() >= pipeline.getBatchSize()) {
                        flushBatch(conn, updatePs, trackingPs, pending, hostname, threadName);
                        log.debug("[{}] Committed batch, total: {}", threadName, totalCount);
                    }
                } finally {
                    writerSemaphore.release();
                }
            }

            // Final batch commit
            if (!pending.isEmpty()) {
                flushBatch(conn, updatePs, trackingPs, pending, hostname, threadName);
                log.debug("[{}] Final commit, total: {}", threadName, totalCount);
            }
        }
        // Deliberately no catch here: a writer failure (SQLException, unchecked, Error) must
        // propagate to runWriters(), which marks the writer stage dead. Swallowing it here is
        // what used to leave the result queue undrained and hang the entire run.
    }

    private void bindUpdate(PreparedStatement updatePs, CompressionResult.Success result) throws SQLException {
        // WHERE OTTI_ID = ? AND OTTI_CTR = ? - the detail table's composite PK
        updatePs.setBinaryStream(1, new ByteArrayInputStream(result.compressedData()),
                result.compressedData().length);
        updatePs.setLong(2, result.id());
        updatePs.setLong(3, result.ctr());
    }

    private void bindSuccessTracking(PreparedStatement trackingPs, CompressionResult.Success result,
                                     String hostname) throws SQLException {
        double savingsPercent = 100.0 * (1 - (double) result.compressedSize() / result.originalSize());
        trackingPs.setLong(1, result.id());
        trackingPs.setLong(2, result.ctr());
        trackingPs.setLong(3, result.originalSize());
        trackingPs.setLong(4, result.compressedSize());
        trackingPs.setDouble(5, savingsPercent);
        trackingPs.setString(6, hostname);
    }

    /**
     * Execute and commit the pending batch, but only once every UPDATE is known to have hit its row.
     *
     * <p>The UPDATE and the 'SUCCESS' tracking MERGE share one transaction, so an UPDATE that matches
     * no row (detail row deleted meanwhile, or a CTR that no longer exists) would otherwise be
     * committed alongside a tracking row claiming the PDF was compressed - and the anti-join would
     * then skip it forever. If any row count is zero the whole batch is rolled back and replayed one
     * record at a time, so the sound records still land and only the bad ones are dropped.
     *
     * <p>{@code pending} is cleared on every exit path, including a failure that propagates.
     */
    private void flushBatch(Connection conn, PreparedStatement updatePs, PreparedStatement trackingPs,
                            List<CompressionResult.Success> pending, String hostname, String threadName)
            throws SQLException {
        try {
            int[] counts = updatePs.executeBatch();
            trackingPs.executeBatch();

            if (allRowsUpdated(counts)) {
                conn.commit();
                pending.forEach(r -> tracker.recordUpdate());
                return;
            }

            conn.rollback();
            replayBatch(conn, updatePs, trackingPs, pending, hostname, threadName);
        } finally {
            pending.clear();
        }
    }

    /**
     * True if every batched UPDATE matched exactly one row, or if the driver refused to say.
     * Oracle's JDBC driver is entitled to return {@link Statement#SUCCESS_NO_INFO} instead of a real
     * count; that is accepted (there is nothing else we can do), but it means a zero-row UPDATE is
     * undetectable, which is worth exactly one warning per run.
     */
    private boolean allRowsUpdated(int[] counts) {
        boolean verified = true;
        for (int count : counts) {
            if (count == Statement.SUCCESS_NO_INFO) {
                warnRowCountsUnavailable();
            } else if (count != 1) {
                verified = false;
            }
        }
        return verified;
    }

    private void warnRowCountsUnavailable() {
        if (rowCountWarningIssued.compareAndSet(false, true)) {
            log.warn("JDBC driver reports SUCCESS_NO_INFO for batched UPDATEs: row counts are not "
                    + "available, so UPDATEs matching zero rows cannot be detected and such records "
                    + "may still be recorded as SUCCESS");
        }
    }

    /**
     * Re-apply a rolled-back batch record by record, each in its own transaction, so that one missing
     * detail row does not cost the whole batch. Outside a batch the driver always reports a real row
     * count, so here the zero-row case is unambiguous.
     */
    private void replayBatch(Connection conn, PreparedStatement updatePs, PreparedStatement trackingPs,
                             List<CompressionResult.Success> pending, String hostname, String threadName)
            throws SQLException {
        log.warn("[{}] Batch rolled back: at least one UPDATE matched no row. Replaying {} record(s) individually",
                threadName, pending.size());

        for (CompressionResult.Success result : pending) {
            bindUpdate(updatePs, result);
            int rows = updatePs.executeUpdate();

            if (rows == 1) {
                bindSuccessTracking(trackingPs, result, hostname);
                trackingPs.executeUpdate();
                conn.commit();
                tracker.recordUpdate();
                continue;
            }

            // Nothing is committed for this record: it must not be marked as compressed, or the
            // resume anti-join would skip it on every future run.
            conn.rollback();
            log.error("[{}] Detail row id={} ctr={} not found (UPDATE matched {} row(s)); "
                            + "NOT marked as compressed",
                    threadName, result.id(), result.ctr(), rows);

            var missing = new IllegalStateException(
                    "Detail row id=%d ctr=%d not found at write time".formatted(result.id(), result.ctr()));
            tracker.recordError(result.id(), missing);
            trackNonSuccess(CompressionResult.Failure.of(
                    result.id(), result.ctr(), result.filename(), missing.getMessage()));
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
     * Uses MERGE (upsert) to handle duplicate records.
     */
    private void trackNonSuccess(CompressionResult result) {
        var q = properties.getQuery();
        String hostname = getHostname();

        if (result instanceof CompressionResult.Skipped skipped) {
            String sql = String.format("""
                MERGE INTO %s T
                USING (SELECT ? AS OTT_ID, ? AS OTT_CTR, ? AS ORIG_SIZE, ? AS ERR_MSG, ? AS HOST FROM DUAL) S
                ON (T.OTT_ID = S.OTT_ID AND T.OTT_CTR = S.OTT_CTR)
                WHEN MATCHED THEN UPDATE SET
                    T.ORIGINAL_SIZE = S.ORIG_SIZE, T.STATUS = 'SKIPPED',
                    T.ERROR_MESSAGE = S.ERR_MSG, T.HOSTNAME = S.HOST, T.PROCESSED_DATE = SYSTIMESTAMP
                WHEN NOT MATCHED THEN INSERT (OTT_ID, OTT_CTR, ORIGINAL_SIZE, STATUS, ERROR_MESSAGE, HOSTNAME)
                    VALUES (S.OTT_ID, S.OTT_CTR, S.ORIG_SIZE, 'SKIPPED', S.ERR_MSG, S.HOST)
                """, q.getTrackingTable());

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, skipped.id());
                ps.setLong(2, skipped.ctr());
                ps.setLong(3, skipped.size());
                ps.setString(4, skipped.reason());
                ps.setString(5, hostname);
                ps.executeUpdate();
                conn.commit();
            } catch (Exception e) {
                log.warn("Failed to track skipped ID {} CTR {}: {}", skipped.id(), skipped.ctr(), e.getMessage());
            }
        } else if (result instanceof CompressionResult.Failure failure) {
            String sql = String.format("""
                MERGE INTO %s T
                USING (SELECT ? AS OTT_ID, ? AS OTT_CTR, ? AS ERR_MSG, ? AS HOST FROM DUAL) S
                ON (T.OTT_ID = S.OTT_ID AND T.OTT_CTR = S.OTT_CTR)
                WHEN MATCHED THEN UPDATE SET
                    T.ORIGINAL_SIZE = 0, T.STATUS = 'ERROR',
                    T.ERROR_MESSAGE = S.ERR_MSG, T.HOSTNAME = S.HOST, T.PROCESSED_DATE = SYSTIMESTAMP
                WHEN NOT MATCHED THEN INSERT (OTT_ID, OTT_CTR, ORIGINAL_SIZE, STATUS, ERROR_MESSAGE, HOSTNAME)
                    VALUES (S.OTT_ID, S.OTT_CTR, 0, 'ERROR', S.ERR_MSG, S.HOST)
                """, q.getTrackingTable());

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, failure.id());
                ps.setLong(2, failure.ctr());
                ps.setString(3, truncate(failure.errorMessage(), 500));
                ps.setString(4, hostname);
                ps.executeUpdate();
                conn.commit();
            } catch (Exception e) {
                log.warn("Failed to track error ID {} CTR {}: {}", failure.id(), failure.ctr(), e.getMessage());
            }
        }
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private void runDryRunWriter(String threadName) throws InterruptedException {
        while (true) {
            CompressionResult.Success result = resultHandoff.take();

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

    /**
     * Bounded hand-off between one pipeline stage and the next, aware of whether the consuming
     * stage is still alive.
     *
     * <p>The stages used to be connected by a plain {@link ArrayBlockingQueue} and a blocking
     * {@code put()}. When a consumer died (unchecked exception, fatal SQLException, Error) nothing
     * drained the queue any more: it filled up, every producer blocked forever in {@code put()},
     * and the run never terminated and never failed - it just hung, holding the Hikari pool open.
     *
     * <p>{@link #put} therefore polls with {@link BlockingQueue#offer(Object, long, TimeUnit)}
     * instead of blocking indefinitely, and between polls re-checks the consuming stage's
     * liveness. Once the stage has failed, or once no consumer is left at all, {@code put} throws
     * {@link PipelineAbortedException} rather than waiting for a consumer that is never coming
     * back. A healthy but slow consumer is unaffected: {@code put} keeps retrying for as long as
     * the consumers live, so the normal poison-pill shutdown is unchanged.
     *
     * <p>The consumer count starts at the number of consumers the stage <em>will</em> have
     * (known up front: one per worker thread), so a producer that starts before its consumers
     * cannot mistake "not started yet" for "all dead".
     */
    static final class Handoff<T> {

        private final String consumerStage;
        private final BlockingQueue<T> queue;
        private final long offerPollMillis;
        private final AtomicInteger liveConsumers;
        private final AtomicReference<Throwable> fatalFailure = new AtomicReference<>();

        Handoff(String consumerStage, int capacity, int expectedConsumers, long offerPollMillis) {
            this.consumerStage = consumerStage;
            this.queue = new ArrayBlockingQueue<>(capacity);
            this.offerPollMillis = offerPollMillis;
            this.liveConsumers = new AtomicInteger(expectedConsumers);
        }

        /** A consumer of this hand-off ended normally (it drained its poison pill). */
        void consumerFinished() {
            liveConsumers.decrementAndGet();
        }

        /** A consumer of this hand-off died. The first cause wins and aborts the stage. */
        void consumerFailed(Throwable cause) {
            fatalFailure.compareAndSet(null, cause);
            liveConsumers.decrementAndGet();
        }

        /** The first fatal failure of a consumer, or null if none died. */
        Throwable fatalFailure() {
            return fatalFailure.get();
        }

        int liveConsumers() {
            return liveConsumers.get();
        }

        int pending() {
            return queue.size();
        }

        /**
         * Hand an item to the consuming stage, waiting for as long as it takes - but only while
         * there is still someone to hand it to.
         *
         * @throws PipelineAbortedException if the consuming stage has failed or has no live
         *         consumer left, i.e. this item could never be consumed
         */
        void put(T item) throws InterruptedException {
            while (true) {
                Throwable failure = fatalFailure.get();
                if (failure != null) {
                    throw new PipelineAbortedException(
                            "the " + consumerStage + " stage failed: " + failure, failure);
                }
                if (liveConsumers.get() <= 0) {
                    throw new PipelineAbortedException(
                            "no live consumer left in the " + consumerStage + " stage", failure);
                }
                if (queue.offer(item, offerPollMillis, TimeUnit.MILLISECONDS)) {
                    return;
                }
                // Queue full: loop round and re-check that the consuming stage is still alive.
            }
        }

        /**
         * Enqueue a shutdown sentinel (poison pill / id == -1 result). Retries while any consumer
         * is still alive - a live consumer keeps draining, so space appears - and gives up once
         * they are all gone, since there is then nobody left to notify.
         */
        void putSentinel(T sentinel) throws InterruptedException {
            while (liveConsumers.get() > 0) {
                if (queue.offer(sentinel, offerPollMillis, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
            log.debug("No live consumer in the {} stage; dropping shutdown sentinel", consumerStage);
        }

        T take() throws InterruptedException {
            return queue.take();
        }

        /** Drop everything still queued; used once the run has been aborted. */
        void discardPending() {
            queue.clear();
        }
    }

    /**
     * Thrown out of {@link #run()} when a pipeline stage died. The run is aborted and the cause
     * propagated instead of the process hanging on a queue nobody drains.
     */
    public static final class PipelineFailedException extends RuntimeException {
        public PipelineFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Internal signal: the consuming stage this thread is feeding is gone, so this thread must
     * stop rather than block on a hand-off forever. Never escapes {@link #run()}.
     */
    static final class PipelineAbortedException extends RuntimeException {
        PipelineAbortedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
