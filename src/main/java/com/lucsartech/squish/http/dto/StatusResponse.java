package com.lucsartech.squish.http.dto;

import com.lucsartech.squish.pipeline.ProgressTracker;

import java.util.List;

/**
 * Dashboard status payload. Field names are a public JSON contract - do not rename.
 */
public record StatusResponse(ProgressTracker.Snapshot data,
                             String mode,
                             String compressionMode,
                             boolean watchMode,
                             SystemInfo system,
                             List<ProgressTracker.ActivityEntry> activity,
                             ActiveProfileInfo profile,
                             OnDemandInfo onDemand,
                             String version) {}
