package com.lucsartech.squish.http;

import com.lucsartech.squish.BuildInfo;
import com.lucsartech.squish.config.SquishProperties;
import com.lucsartech.squish.http.dto.ActiveProfileInfo;
import com.lucsartech.squish.http.dto.HealthResponse;
import com.lucsartech.squish.http.dto.OnDemandInfo;
import com.lucsartech.squish.http.dto.ProfileInfo;
import com.lucsartech.squish.http.dto.ProfilesResponse;
import com.lucsartech.squish.http.dto.StatusResponse;
import com.lucsartech.squish.http.dto.SystemInfo;
import com.lucsartech.squish.metrics.SquishMetrics;
import com.lucsartech.squish.pipeline.ProgressTracker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;

/**
 * Read-only status/health/profile JSON API. The field names below are a public
 * contract consumed by the dashboard JS and by external monitoring clients.
 */
@RestController
public class StatusController {

    private final ProgressTracker tracker;
    private final SquishProperties properties;

    public StatusController(ProgressTracker tracker, SquishProperties properties) {
        this.tracker = tracker;
        this.properties = properties;
    }

    @GetMapping("/api/status")
    public StatusResponse status() {
        var snapshot = tracker.snapshot();
        boolean dryRun = properties.isDryRun();
        boolean watchMode = properties.getWatchdog().isEnabled();

        String modeStr = dryRun ? "DRY-RUN" : "NORMAL";
        if (watchMode) {
            modeStr = dryRun ? "WATCHDOG (DRY-RUN)" : "WATCHDOG";
        }

        var system = getSystemInfo();
        var activity = tracker.recentActivity();
        return new StatusResponse(snapshot, modeStr, properties.getMode().name(),
                watchMode, system, activity,
                getActiveProfileInfo(), getOnDemandInfo(),
                BuildInfo.version(), BuildInfo.buildNumber(), BuildInfo.buildTime());
    }

    private ActiveProfileInfo getActiveProfileInfo() {
        var profile = properties.getActiveCompressionProfile();
        if (profile == null) {
            // Same fallback as activeProfile(): a default keyed on the legacy mode.
            return new ActiveProfileInfo(
                    "default",
                    properties.getMode() != null ? properties.getMode().name() : "MEDIUM",
                    1.0f, 1.0f, true,
                    false, null, null, 0.0f,
                    false, null);
        }
        return new ActiveProfileInfo(
                profile.getName(),
                profile.getMode() != null ? profile.getMode().name() : "CUSTOM",
                profile.getEffectiveScaleFactor(),
                profile.getEffectiveJpegQuality(),
                profile.isLossless(),
                profile.isWatermarkEnabled(),
                profile.getWatermarkText(),
                profile.getWatermarkPosition() != null ? profile.getWatermarkPosition().name() : null,
                profile.getWatermarkOpacity(),
                profile.isPdfaEnabled(),
                profile.getPdfaConformance() != null ? profile.getPdfaConformance().name() : null);
    }

    private OnDemandInfo getOnDemandInfo() {
        var metrics = SquishMetrics.getInstance();
        return new OnDemandInfo(
                metrics.getOnDemandCalls(),
                metrics.getOnDemandAvgMs(),
                metrics.getOnDemandLastFilename(),
                metrics.getOnDemandLastSavingsPercent(),
                metrics.isOnDemandLastSuccess());
    }

    private SystemInfo getSystemInfo() {
        Runtime rt = Runtime.getRuntime();
        long maxMemory = rt.maxMemory();
        long totalMemory = rt.totalMemory();
        long freeMemory = rt.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        double memoryPercent = maxMemory > 0 ? (double) usedMemory / maxMemory * 100.0 : 0;
        int activeThreads = ManagementFactory.getThreadMXBean().getThreadCount();

        double cpuPercent = 0;
        var osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
            cpuPercent = sunOsBean.getProcessCpuLoad() * 100.0;
            if (cpuPercent < 0) cpuPercent = 0; // -1 means not available
        }
        int availableProcessors = osBean.getAvailableProcessors();

        return new SystemInfo(usedMemory, totalMemory, maxMemory, freeMemory, memoryPercent,
                activeThreads, cpuPercent, availableProcessors);
    }

    @GetMapping("/api/health")
    public HealthResponse health() {
        // version + buildNumber here too, so a lightweight monitoring probe can read the running
        // build over HTTP without pulling the full status payload.
        return new HealthResponse("UP", tracker.isCompleted() ? "COMPLETED" : "RUNNING",
                BuildInfo.version(), BuildInfo.buildNumber());
    }

    @GetMapping("/api/profiles")
    public ProfilesResponse profiles() {
        var profileList = properties.getProfiles().entrySet().stream()
                .map(e -> new ProfileInfo(
                        e.getKey(),
                        e.getValue().getDescription(),
                        e.getValue().getMode() != null ? e.getValue().getMode().name() : "CUSTOM",
                        e.getValue().getEffectiveScaleFactor(),
                        e.getValue().getEffectiveJpegQuality(),
                        e.getValue().isLossless(),
                        e.getValue().isWatermarkEnabled(),
                        e.getValue().isPdfaEnabled()
                ))
                .toList();

        return new ProfilesResponse(profileList, properties.getActiveProfile());
    }

    @GetMapping("/api/profile")
    public ProfileInfo activeProfile() {
        var activeProfile = properties.getActiveCompressionProfile();
        if (activeProfile == null) {
            return new ProfileInfo(
                    "default",
                    "Default compression profile",
                    properties.getMode() != null ? properties.getMode().name() : "MEDIUM",
                    1.0f, 1.0f, true, false, false
            );
        }

        return new ProfileInfo(
                activeProfile.getName(),
                activeProfile.getDescription(),
                activeProfile.getMode() != null ? activeProfile.getMode().name() : "CUSTOM",
                activeProfile.getEffectiveScaleFactor(),
                activeProfile.getEffectiveJpegQuality(),
                activeProfile.isLossless(),
                activeProfile.isWatermarkEnabled(),
                activeProfile.isPdfaEnabled()
        );
    }
}
