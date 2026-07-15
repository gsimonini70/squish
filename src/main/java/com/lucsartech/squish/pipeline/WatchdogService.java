package com.lucsartech.squish.pipeline;

import com.lucsartech.squish.compression.Squish;
import com.lucsartech.squish.compression.CompressionResult;
import com.lucsartech.squish.config.SquishProperties;
import com.lucsartech.squish.email.EmailService;
import com.lucsartech.squish.report.ReportGenerator;
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
import java.util.function.Consumer;

/**
 * Watchdog service for continuous PDF compression monitoring.
 * Polls the database for new records and processes them automatically.
 */
public final class WatchdogService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WatchdogService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final SquishProperties properties;
    private final ProgressTracker tracker;
    private final Squish compressor;
    private final HikariDataSource dataSource;
    private final EmailService emailService;

    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final Semaphore workerSemaphore;
    /** Producer-side backpressure: caps how many PDF byte[] may be on the heap at once. */
    private final Semaphore inFlightSemaphore;
    private final int maxInFlight;

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

    public WatchdogService(SquishProperties properties, ProgressTracker tracker) {
        this.properties = properties;
        this.tracker = tracker;
        this.compressor = new Squish(properties.getActiveCompressionProfile());
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

        // Backpressure bound. The batch path bounds in-flight work with an
        // ArrayBlockingQueue; this path has no queue, so the producer loop is gated by
        // permits instead. The bound is derived from worker-threads (2x, so a worker
        // never starves while the producer fetches the next BLOB) and therefore needs no
        // new configuration property: at most `maxInFlight` PDF byte[] exist at any moment,
        // regardless of how many rows the cycle's result set returns.
        this.maxInFlight = Math.max(2, properties.getPipeline().getWorkerThreads() * 2);
        this.inFlightSemaphore = new Semaphore(maxInFlight);

        // Initialize with the starting ID from config
        this.lastProcessedId = new AtomicLong(properties.getPipeline().getIdFrom());

        log.info("Watchdog initialized - polling every {} seconds, starting from ID {}, max {} PDFs in flight{}",
                properties.getWatchdog().getPollIntervalSeconds(), lastProcessedId.get(), maxInFlight,
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
        // The anti-join matches the DETAIL row on both PK columns: a master document may own N
        // detail rows, and each of them is tracked separately. Matching on the master ID alone
        // would exclude a whole document as soon as any one of its parts had been processed.
        // OTT_ID / OTT_CTR are the tracking table's own column names and are hardcoded here by
        // design (see CLAUDE.md); only the source-side names come from configuration.
        String sql = String.format("""
            SELECT COUNT(*) AS cnt, NVL(SUM(DBMS_LOB.GETLENGTH(%s)), 0) AS total_size
            FROM %s
            INNER JOIN %s ON %s = %s
            WHERE (%s)
              AND %s IS NOT NULL
              AND %s >= ?
              AND NOT EXISTS (SELECT 1 FROM %s SP
                               WHERE SP.OTT_ID  = %s.%s
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
     * Run a single compression cycle. This is the task handed to
     * {@link ScheduledExecutorService#scheduleAtFixedRate}, so nothing may ever escape it:
     * per that contract a task that throws is silently cancelled and never runs again,
     * leaving a live-looking JVM (Tomcat still serving the dashboard) that processes nothing.
     */
    private void runCycle() {
        long cycle = cycleCount.incrementAndGet();
        runGuarded(cycle, () -> executeCycle(cycle), this::handleFatalError);
    }

    /**
     * Guard around a scheduled cycle body. Catches everything so the schedule can never be
     * cancelled by a throwing task.
     *
     * <p>An {@link Exception} is transient (a DB blip, a bad record): log it and let the next
     * cycle run. An {@link Error} (most realistically {@link OutOfMemoryError}) is not
     * recoverable: continuing would produce garbage results while looking healthy, so the
     * fatal handler stops the watchdog and terminates the process — a crash is something an
     * init script / systemd / Docker restart policy can act on, a silent zombie is not.
     *
     * <p>Package-private and static: it is the testable seam for the "a throwing cycle must
     * not cancel the schedule" contract, and needs no database.
     */
    static void runGuarded(long cycle, Runnable cycleBody, Consumer<Throwable> fatalErrorHandler) {
        try {
            cycleBody.run();
        } catch (Exception e) {
            log.error("Cycle #{} failed - watchdog continues with the next cycle", cycle, e);
        } catch (Throwable t) {
            log.error("FATAL: cycle #{} threw {} - the watchdog cannot continue safely",
                    cycle, t.getClass().getName(), t);
            try {
                fatalErrorHandler.accept(t);
            } catch (Throwable secondary) {
                log.error("Fatal error handler failed", secondary);
            }
        }
    }

    /**
     * Stop the watchdog and terminate the JVM after an unrecoverable {@link Error}.
     * Uses the existing shutdown path: {@code System.exit} fires the shutdown hook registered
     * by {@code SquishApplication}, which releases its latch and calls {@link #close()}.
     */
    private void handleFatalError(Throwable fatal) {
        running.set(false);
        scheduler.shutdown(); // no further cycles, whatever happens below
        log.error("Watchdog terminating with exit code 1 after unrecoverable error");
        System.exit(1);
    }

    /**
     * Body of a single compression cycle. Callers must run it through {@link #runGuarded}.
     */
    private void executeCycle(long cycle) {
        String timeStr = LocalDateTime.now().format(TIME_FMT);

        // Reset cycle-specific counters
        cycleOriginalBytes.set(0);
        cycleCompressedBytes.set(0);

        log.info("=== Cycle #{} started at {} (last ID: {}) ===",
                cycle, timeStr, lastProcessedId.get());

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

        // Query for new records (excluding already processed, respecting ID range).
        // Include CTR column for composite PK (OTTI_ID, OTTI_CTR); the anti-join is per detail
        // row, so parts 2..N of a document interrupted mid-way are still picked up on a later
        // cycle. Anything less loses them permanently while tracking reports SUCCESS.
        String sql = String.format("""
            SELECT %s, %s, %s, %s
            FROM %s
            INNER JOIN %s ON %s = %s
            WHERE (%s)
              AND %s IS NOT NULL
              AND %s >= ?
              AND NOT EXISTS (SELECT 1 FROM %s SP
                               WHERE SP.OTT_ID  = %s.%s
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
                    final long id = rs.getLong(q.getIdColumn());
                    final long ctr = rs.getLong(q.getDetailCtrColumn());
                    final String filename = rs.getString(q.getFilenameColumn());

                    // The BLOB is read *inside* submitThrottled, i.e. only once an in-flight
                    // permit has been granted. That is what bounds heap usage: without it this
                    // loop would rip through the whole result set, materialising every PDF.
                    CompletableFuture<Void> future = submitThrottled(
                            inFlightSemaphore,
                            workerSemaphore,
                            executor,
                            () -> rs.getBinaryStream(q.getDataColumn()).readAllBytes(),
                            pdfData -> processRecord(id, ctr, filename, pdfData));

                    tracker.recordRead();

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

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Record production interrupted after {} records - aborting cycle", processedCount);
        } catch (Exception e) {
            log.error("Error processing new records", e);
            tracker.recordError(-1, e);
        }

        return processedCount;
    }

    /** Loads one PDF BLOB out of the result set. May throw the JDBC/IO checked exceptions. */
    @FunctionalInterface
    interface BlobLoader {
        byte[] load() throws Exception;
    }

    /**
     * Producer-side backpressure gate: acquire an in-flight permit, load the BLOB, hand it to
     * a worker. The permit is held for the whole life of the {@code byte[]} and released by the
     * worker when the record is done, so at most {@code inFlight.permits} PDFs are on the heap
     * at any moment however fast the database feeds rows.
     *
     * <p>Acquiring inside the async task (as this class used to) throttles nothing: the producer
     * runs ahead, materialising every BLOB of the cycle and pinning it through the pending future.
     *
     * <p>Permits cannot leak: the worker releases in a {@code finally}, and if the load or the
     * submission fails before hand-off the producer releases its own permit.
     *
     * <p>Package-private and static: this is the testable seam for the bound, and needs no database.
     */
    static CompletableFuture<Void> submitThrottled(Semaphore inFlight,
                                                   Semaphore workerSemaphore,
                                                   Executor executor,
                                                   BlobLoader blobLoader,
                                                   Consumer<byte[]> recordProcessor)
            throws Exception {

        inFlight.acquire(); // producer blocks here when the pipeline is saturated
        boolean handedOff = false;
        try {
            byte[] pdfData = blobLoader.load();

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    workerSemaphore.acquire();
                    try {
                        recordProcessor.accept(pdfData);
                    } finally {
                        workerSemaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.release();
                }
            }, executor);

            handedOff = true;
            return future;
        } finally {
            // Nobody will ever release this permit for us (load failed, or the executor
            // rejected the task), so release it here.
            if (!handedOff) {
                inFlight.release();
            }
        }
    }

    /**
     * Process a single record.
     */
    private void processRecord(long id, long ctr, String filename, byte[] pdfData) {
        CompressionResult result = compressor.compress(id, ctr, filename, pdfData);
        tracker.recordResult(result);

        if (result instanceof CompressionResult.Success success) {
            // Track cycle-specific bytes for email reports
            cycleOriginalBytes.addAndGet(success.originalSize());
            cycleCompressedBytes.addAndGet(success.compressedSize());

            // Only a committed write counts. updateDatabase() returns false when the detail row was
            // not found or the write failed, and those records are already recorded as errors.
            if (!properties.isDryRun()) {
                if (updateDatabase(success)) {
                    tracker.recordUpdate();
                }
            } else {
                log.debug("DRY-RUN: would update ID {} ({} -> {} bytes)",
                        id, success.originalSize(), success.compressedSize());
                tracker.recordUpdate();
            }
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
     *
     * @return true only if the BLOB was actually rewritten and committed. A false return means the
     *         detail row was not found, or the write failed: the caller must not count the record
     *         as updated.
     */
    private boolean updateDatabase(CompressionResult.Success result) {
        var q = properties.getQuery();
        // Use composite PK (OTTI_ID, OTTI_CTR) for update
        String updateSql = String.format("UPDATE %s SET %s = ? WHERE %s = ? AND %s = ?",
                q.getDetailTable(), q.getDataColumn(), q.getDetailIdColumn(), q.getDetailCtrColumn());
        // Use MERGE to handle duplicate records (upsert). Keyed on (OTT_ID, OTT_CTR): one tracking
        // row per detail row, otherwise the N parts of a document overwrite each other and the
        // stored sizes are those of whichever part happened to commit last.
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

        try (Connection conn = dataSource.getConnection();
             PreparedStatement updatePs = conn.prepareStatement(updateSql);
             PreparedStatement trackingPs = conn.prepareStatement(trackingSql)) {

            // Update compressed data (WHERE OTTI_ID = ? AND OTTI_CTR = ?)
            updatePs.setBinaryStream(1,
                    new ByteArrayInputStream(result.compressedData()),
                    result.compressedData().length);
            updatePs.setLong(2, result.id());
            updatePs.setLong(3, result.ctr());
            int rows = updatePs.executeUpdate();

            // The tracking row is the claim "this BLOB is now compressed". Writing it after an
            // UPDATE that matched nothing would make the claim false, and the anti-join would then
            // hide the record forever. The pool runs with autoCommit=false, so nothing has been
            // committed yet and the rollback is enough to leave the row untouched.
            if (rows != 1) {
                conn.rollback();
                reportMissingDetailRow(result, rows);
                return false;
            }

            // Track in SQUISH_PROCESSED (upsert)
            double savingsPercent = 100.0 * (1 - (double) result.compressedSize() / result.originalSize());
            trackingPs.setLong(1, result.id());
            trackingPs.setLong(2, result.ctr());
            trackingPs.setLong(3, result.originalSize());
            trackingPs.setLong(4, result.compressedSize());
            trackingPs.setDouble(5, savingsPercent);
            trackingPs.setString(6, getHostname());
            trackingPs.executeUpdate();

            conn.commit();
            return true;

        } catch (Exception e) {
            log.error("Failed to update ID {} CTR {}", result.id(), result.ctr(), e);
            tracker.recordError(result.id(), e);
            return false;
        }
    }

    /**
     * The detail row addressed by (id, ctr) did not exist when the writer got there — deleted or
     * re-keyed between the read and the write. The compressed bytes have nowhere to go.
     *
     * <p>Records it as an ERROR rather than dropping it: a missing target is a real failure, and the
     * tracking row keeps a later cycle from picking the record up again and failing identically.
     * Called only from the {@code rows != 1} branch, which returns immediately afterwards, so the
     * record reaches {@link ProgressTracker#recordError} exactly once.
     */
    private void reportMissingDetailRow(CompressionResult.Success result, int rows) {
        String message = "Detail row not found (%s=%d, %s=%d): UPDATE matched %d rows - NOT marked as compressed"
                .formatted(properties.getQuery().getDetailIdColumn(), result.id(),
                        properties.getQuery().getDetailCtrColumn(), result.ctr(), rows);

        log.error(message);

        var failure = CompressionResult.Failure.of(result.id(), result.ctr(), result.filename(), message);
        tracker.recordError(result.id(), new IllegalStateException(message));
        trackFailure(failure);
    }

    /**
     * Track a skipped (non-PDF) record in the tracking table.
     */
    private void trackSkipped(CompressionResult.Skipped result) {
        var q = properties.getQuery();
        // Use MERGE to handle duplicate records (upsert), keyed on the composite (OTT_ID, OTT_CTR)
        String trackingSql = String.format("""
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
             PreparedStatement ps = conn.prepareStatement(trackingSql)) {

            ps.setLong(1, result.id());
            ps.setLong(2, result.ctr());
            ps.setLong(3, result.size());
            ps.setString(4, result.reason());
            ps.setString(5, getHostname());
            ps.executeUpdate();
            conn.commit();

        } catch (Exception e) {
            log.error("Failed to track skipped ID {} CTR {}", result.id(), result.ctr(), e);
        }
    }

    /**
     * Track a failed record in the tracking table.
     */
    private void trackFailure(CompressionResult.Failure result) {
        var q = properties.getQuery();
        // Use MERGE to handle duplicate records (upsert), keyed on the composite (OTT_ID, OTT_CTR)
        String trackingSql = String.format("""
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
             PreparedStatement ps = conn.prepareStatement(trackingSql)) {

            ps.setLong(1, result.id());
            ps.setLong(2, result.ctr());
            ps.setString(3, truncate(result.errorMessage(), 500));
            ps.setString(4, getHostname());
            ps.executeUpdate();
            conn.commit();

        } catch (Exception e) {
            log.error("Failed to track error ID {} CTR {}", result.id(), result.ctr(), e);
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
