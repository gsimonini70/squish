package com.lucsartech.squish.http.dto;

import java.util.List;

/**
 * Response for {@code GET /api/scope}: the current runtime scope override plus the configured
 * baseline and the choices an operator may pick from. Field names are a public contract consumed by
 * the config-page JS.
 *
 * @param active           whether a runtime override is currently in effect
 * @param idFrom           override lower id bound (meaningful only when {@code active})
 * @param idTo             override upper id bound, 0 = no limit (meaningful only when {@code active})
 * @param docType          override document type, or null to mean "configured filter"
 * @param autoRevert       whether the override auto-clears when its window drains
 * @param appliedAt        ISO-8601 instant the override was applied, or null
 * @param appliedBy        principal that applied the override, or null
 * @param effectiveFilter  the master-table WHERE fragment that will actually run next cycle
 * @param baselineFilter   the configured {@code squish.query.master-table-filter}
 * @param docTypeColumn    the column an allow-listed doc-type is matched against
 * @param allowedDocTypes  the doc-types an operator may choose (empty = selector disabled)
 * @param watchdogEnabled  whether this instance runs in watchdog mode (scope override only affects that mode)
 * @param pollIntervalSeconds the watchdog poll interval, for the "applies next cycle" hint
 */
public record ScopeResponse(
        boolean active,
        long idFrom,
        long idTo,
        String docType,
        boolean autoRevert,
        String appliedAt,
        String appliedBy,
        String effectiveFilter,
        String baselineFilter,
        String docTypeColumn,
        List<String> allowedDocTypes,
        boolean watchdogEnabled,
        int pollIntervalSeconds
) {}
