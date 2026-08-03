package com.lucsartech.squish.http.dto;

/**
 * Request body for {@code POST /api/scope}. All fields are boxed so "absent" is distinguishable
 * from a value: a {@code null} field falls back to its default.
 *
 * <ul>
 *   <li>{@code active} — {@code false} clears the override (back to the configured scope); {@code true}
 *       (or absent) applies the override built from the other fields.</li>
 *   <li>{@code idFrom} — inclusive lower bound (default 0). Bound as a SQL parameter.</li>
 *   <li>{@code idTo} — inclusive upper bound, 0 = no limit (default 0). Bound as a SQL parameter.</li>
 *   <li>{@code docType} — a document type from {@code squish.query.allowed-doc-types}; {@code null}/blank
 *       keeps the configured master-table filter. Any other value is rejected with 400 — this is the
 *       gate that keeps free text out of the interpolated SQL.</li>
 *   <li>{@code autoRevert} — when true, the watchdog clears the override automatically once its window
 *       is drained (default false).</li>
 * </ul>
 */
public record ScopeUpdateRequest(
        Boolean active,
        Long idFrom,
        Long idTo,
        String docType,
        Boolean autoRevert
) {}
