package com.lucsartech.squish.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime, in-memory override of the watchdog's <em>execution scope</em> (the id range and,
 * optionally, the document type it processes). It lets a trusted dashboard operator steer the
 * watchdog onto a specific sub-scope — to advance processing there before switching to the
 * continuous, full-scope mode — without editing {@code squish.env} or restarting the service.
 *
 * <p><b>Why this is safe (case (a) only).</b> Changing the scope can never re-process or corrupt
 * already-done work: every cycle still gates candidates on the {@code NOT EXISTS} anti-join against
 * the tracking table (keyed on {@code OTT_ID, OTT_CTR}). An override that overlaps processed records
 * simply skips them. So the override can only ever <em>advance not-yet-done records</em> inside a
 * narrower/shifted window; it is inherently idempotent. Re-processing already-tracked records is
 * deliberately <b>not</b> possible through this path.
 *
 * <p><b>Not persisted.</b> Like the runtime {@code activeProfile} switch, the override lives only in
 * memory; a restart reverts to the configured (YAML/env) scope. That is intentional — a "smart but
 * not sysadmin" operator gets a reversible, low-blast-radius lever.
 *
 * <p><b>Not the SQL-injection surface.</b> {@code idFrom}/{@code idTo} are bound parameters. The
 * document type is validated against {@code squish.query.allowed-doc-types} at the HTTP boundary and
 * re-checked by the watchdog before it is ever interpolated, so free text can never reach the SQL.
 *
 * <p>Singleton Spring bean, shared between the {@code ScopeController} (writer) and
 * {@code WatchdogService} (reader, once per cycle). All mutations bump {@link #getVersion()} so the
 * watchdog can detect a change and re-log / recompute its backlog stats.
 */
public final class ScopeState {

    private static final Logger log = LoggerFactory.getLogger(ScopeState.class);

    private volatile boolean active = false;
    private volatile long idFrom = 0;
    private volatile long idTo = 0;          // 0 = no upper bound
    private volatile String docType = null;  // null/blank = keep the configured master-table filter
    private volatile boolean autoRevert = false;
    private volatile Instant appliedAt = null;
    private volatile String appliedBy = null;

    private final AtomicLong version = new AtomicLong(0);

    /**
     * Activate (or replace) the scope override. Validation of {@code idFrom}/{@code idTo} and of
     * {@code docType} against the allow-list happens at the caller (the controller); this method only
     * records the already-validated values and logs the transition.
     *
     * @param docType a document type from the configured allow-list, or {@code null}/blank to keep the
     *                configured master-table filter and override only the id range
     * @param appliedBy the authenticated principal name, for the audit log
     */
    public synchronized void apply(long idFrom, long idTo, String docType,
                                   boolean autoRevert, String appliedBy) {
        this.idFrom = idFrom;
        this.idTo = idTo;
        this.docType = (docType == null || docType.isBlank()) ? null : docType;
        this.autoRevert = autoRevert;
        this.appliedBy = appliedBy;
        this.appliedAt = Instant.now();
        this.active = true;
        long v = version.incrementAndGet();

        log.warn("Scope override APPLIED by '{}' (v{}): idFrom={}, idTo={}, docType={}, autoRevert={}. "
                        + "Takes effect on the next watchdog cycle; already-processed records stay skipped.",
                appliedBy, v, idFrom, (idTo > 0 ? idTo : "∞"),
                (this.docType != null ? this.docType : "<configured filter>"), autoRevert);
    }

    /**
     * Deactivate the override and return to the configured scope. Idempotent: clearing an already
     * inactive override is a no-op (and is not logged as a transition).
     *
     * @param reason short human-readable cause for the audit log (e.g. "operator request",
     *               "window drained (auto-revert)")
     */
    public synchronized void clear(String reason) {
        if (!active) {
            return;
        }
        this.active = false;
        long v = version.incrementAndGet();
        log.warn("Scope override CLEARED (v{}) - reason: {}. The watchdog returns to the configured "
                + "scope on the next cycle.", v, reason);
    }

    public long getVersion() {
        return version.get();
    }

    /** Immutable point-in-time view, so a reader sees a consistent set of fields. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(active, idFrom, idTo, docType, autoRevert, appliedAt, appliedBy, version.get());
    }

    public record Snapshot(
            boolean active,
            long idFrom,
            long idTo,
            String docType,
            boolean autoRevert,
            Instant appliedAt,
            String appliedBy,
            long version
    ) {
        public boolean hasUpperBound() {
            return idTo > 0;
        }
    }
}
