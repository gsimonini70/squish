package com.lucsartech.squish.http.dto;

/**
 * On-demand REST compression counters, process-lifetime (NOT "today").
 * Field names are a public JSON contract - do not rename.
 *
 * lastFilename is user-supplied and untrusted; the frontend renders it via
 * textContent only.
 */
public record OnDemandInfo(long calls, double avgMs, String lastFilename,
                           double lastSavingsPercent, boolean lastSuccess) {}
