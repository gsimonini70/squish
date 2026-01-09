package com.lucsartech.pdf;

import com.lucsartech.pdf.config.PdfCompressorProperties;
import com.lucsartech.pdf.email.EmailService;
import com.lucsartech.pdf.http.MonitorServer;
import com.lucsartech.pdf.pipeline.CompressionPipeline;
import com.lucsartech.pdf.pipeline.ProgressTracker;
import com.lucsartech.pdf.pipeline.WatchdogService;
import com.lucsartech.pdf.report.ReportGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * PDF Compressor Modern - Spring Boot Application.
 *
 * <p>Features:
 * <ul>
 *   <li>Java 22 with Virtual Threads</li>
 *   <li>Spring Boot configuration via YAML</li>
 *   <li>Beautiful real-time monitoring dashboard</li>
 *   <li>Automatic PDF report generation</li>
 *   <li>Email notifications in watchdog mode</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties(PdfCompressorProperties.class)
public class PdfCompressorApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PdfCompressorApplication.class);

    private static final String BANNER = """

            ╔═══════════════════════════════════════════════════════════════╗
            ║                                                               ║
            ║   ██████╗ ██████╗ ███████╗     ██████╗ ██████╗ ███╗   ███╗   ║
            ║   ██╔══██╗██╔══██╗██╔════╝    ██╔════╝██╔═══██╗████╗ ████║   ║
            ║   ██████╔╝██║  ██║█████╗      ██║     ██║   ██║██╔████╔██║   ║
            ║   ██╔═══╝ ██║  ██║██╔══╝      ██║     ██║   ██║██║╚██╔╝██║   ║
            ║   ██║     ██████╔╝██║         ╚██████╗╚██████╔╝██║ ╚═╝ ██║   ║
            ║   ╚═╝     ╚═════╝ ╚═╝          ╚═════╝ ╚═════╝ ╚═╝     ╚═╝   ║
            ║                                                               ║
            ║             Modern PDF Compression Pipeline v2.0              ║
            ║                   Powered by Spring Boot                      ║
            ║                                                               ║
            ╚═══════════════════════════════════════════════════════════════╝
            """;

    private final PdfCompressorProperties properties;
    private final ProgressTracker tracker;
    private final MonitorServer monitorServer;
    private final CompressionPipeline pipeline;
    private final WatchdogService watchdogService;

    @Autowired(required = false)
    private EmailService emailService;

    public PdfCompressorApplication(
            PdfCompressorProperties properties,
            ProgressTracker tracker,
            MonitorServer monitorServer,
            CompressionPipeline pipeline,
            WatchdogService watchdogService) {
        this.properties = properties;
        this.tracker = tracker;
        this.monitorServer = monitorServer;
        this.pipeline = pipeline;
        this.watchdogService = watchdogService;
    }

    public static void main(String[] args) {
        System.out.println(BANNER);
        SpringApplication.run(PdfCompressorApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("Starting PDF Compressor Modern");
        log.info("Mode: {} | Threads: {} | Dry-run: {} | Watch: {}",
                properties.getMode(),
                properties.getPipeline().getWorkerThreads(),
                properties.isDryRun(),
                properties.getWatchdog().isEnabled());

        // Start monitoring server
        monitorServer.start(properties.getHttp().getPort());

        if (properties.getWatchdog().isEnabled()) {
            runWatchdogMode();
        } else {
            runBatchMode();
        }
    }

    /**
     * Run in batch mode (single execution).
     */
    private void runBatchMode() throws Exception {
        try {
            // Calculate initial stats
            log.info("Calculating initial database statistics...");
            pipeline.calculateInitialStats();

            // Run the pipeline
            log.info("Starting compression pipeline...");
            pipeline.run();

            // Calculate final stats
            if (!properties.isDryRun()) {
                log.info("Calculating final database statistics...");
                pipeline.calculateFinalStats();
            }

            // Generate report
            log.info("Generating PDF report...");
            var reportPath = ReportGenerator.generate(tracker, properties, "compression_report");
            if (reportPath != null) {
                log.info("Report saved to: {}", reportPath.toAbsolutePath());
            }

            // Send email notification if configured
            if (emailService != null) {
                var snapshot = tracker.snapshot();
                emailService.sendBatchReport(
                        (int) snapshot.updated(),
                        snapshot.originalBytes() / 1024.0 / 1024.0,
                        snapshot.compressedBytes() / 1024.0 / 1024.0,
                        snapshot.savingsPercent(),
                        (int) snapshot.errors(),
                        snapshot.elapsedSeconds(),
                        reportPath,
                        properties.isDryRun()
                );
            }

            // Print summary
            printSummary();

        } finally {
            pipeline.close();
            monitorServer.close();
        }

        log.info("PDF Compressor Modern completed successfully");
    }

    /**
     * Run in watchdog mode (continuous monitoring).
     */
    private void runWatchdogMode() throws Exception {
        log.info("Starting in WATCHDOG mode - polling every {} seconds",
                properties.getWatchdog().getPollIntervalSeconds());

        var shutdownLatch = new CountDownLatch(1);

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received...");
            shutdownLatch.countDown();
        }));

        try {
            watchdogService.start();

            System.out.println();
            System.out.println("╔═══════════════════════════════════════════════════════════════╗");
            System.out.println("║                    WATCHDOG MODE ACTIVE                       ║");
            System.out.println("╠═══════════════════════════════════════════════════════════════╣");
            System.out.printf("║  Poll interval:  %-44s ║%n",
                    properties.getWatchdog().getPollIntervalSeconds() + " seconds");
            System.out.printf("║  Starting ID:    %-44s ║%n",
                    properties.getPipeline().getIdFrom());
            System.out.printf("║  Monitor:        %-44s ║%n",
                    "http://localhost:" + properties.getHttp().getPort());
            System.out.println("║                                                               ║");
            System.out.println("║  Press Ctrl+C to stop                                         ║");
            System.out.println("╚═══════════════════════════════════════════════════════════════╝");
            System.out.println();

            // Wait for shutdown signal
            shutdownLatch.await();

            // Generate final report
            log.info("Generating final report...");
            var reportPath = ReportGenerator.generate(tracker, properties, "watchdog_report");
            if (reportPath != null) {
                log.info("Report saved to: {}", reportPath.toAbsolutePath());
            }

            printWatchdogSummary();

        } finally {
            watchdogService.close();
            monitorServer.close();
        }

        log.info("Watchdog mode terminated");
    }

    private void printWatchdogSummary() {
        var snapshot = tracker.snapshot();
        var status = watchdogService.getStatus();

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("                    WATCHDOG SUMMARY                            ");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("  Total Cycles:     %,d%n", status.cycleCount());
        System.out.printf("  Last Processed ID: %,d%n", status.lastProcessedId());
        System.out.printf("  Total Runtime:    %s%n", formatElapsed(snapshot.elapsedSeconds()));
        System.out.println("───────────────────────────────────────────────────────────────");
        System.out.printf("  Records Processed: %,d%n", snapshot.updated());
        System.out.printf("  Errors:           %,d%n", snapshot.errors());
        System.out.printf("  Original:         %.2f MB%n", snapshot.originalBytes() / 1024.0 / 1024.0);
        System.out.printf("  Compressed:       %.2f MB%n", snapshot.compressedBytes() / 1024.0 / 1024.0);
        System.out.printf("  Savings:          %.1f%%%n", snapshot.savingsPercent());
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();
    }

    private void printSummary() {
        var snapshot = tracker.snapshot();

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("                      COMPRESSION SUMMARY                       ");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("  Mode:             %s%n", properties.getMode().name());
        System.out.printf("  Duration:         %s%n", formatElapsed(snapshot.elapsedSeconds()));
        System.out.printf("  Records:          %,d processed%n", snapshot.updated());
        System.out.printf("  Errors:           %,d%n", snapshot.errors());
        System.out.println("───────────────────────────────────────────────────────────────");
        System.out.printf("  Original:         %.2f MB%n", snapshot.originalBytes() / 1024.0 / 1024.0);
        System.out.printf("  Compressed:       %.2f MB%n", snapshot.compressedBytes() / 1024.0 / 1024.0);
        System.out.printf("  Compression:      %.1f%% saved%n", snapshot.savingsPercent());
        System.out.println("───────────────────────────────────────────────────────────────");
        System.out.printf("  Throughput:       %.1f records/sec | %.2f MB/sec%n",
                snapshot.recordsPerSecond(), snapshot.mbPerSecond());
        System.out.printf("  Avg Time/PDF:     %d ms%n", snapshot.avgProcessingTimeMs());
        System.out.println("═══════════════════════════════════════════════════════════════");

        if (!tracker.failedIds().isEmpty()) {
            System.out.printf("%n  Failed IDs (DLQ): %s%n", tracker.failedIds());
        }

        System.out.println();
    }

    private String formatElapsed(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0
                ? String.format("%d:%02d:%02d", h, m, s)
                : String.format("%d:%02d", m, s);
    }
}
