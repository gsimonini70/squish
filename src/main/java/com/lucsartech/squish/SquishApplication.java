package com.lucsartech.squish;

import com.lucsartech.squish.config.SquishProperties;
import com.lucsartech.squish.email.EmailService;
import com.lucsartech.squish.pipeline.CompressionPipeline;
import com.lucsartech.squish.pipeline.ProgressTracker;
import com.lucsartech.squish.pipeline.WatchdogService;
import com.lucsartech.squish.report.ReportGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

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
@EnableConfigurationProperties(SquishProperties.class)
public class SquishApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SquishApplication.class);

    /** Interior width of the ASCII banner box, in characters. */
    private static final int BANNER_WIDTH = 63;

    private static final String BANNER_TEMPLATE = """

            ╔═══════════════════════════════════════════════════════════════╗
            ║                                                               ║
            ║   ██████╗ ██████╗ ███████╗     ██████╗ ██████╗ ███╗   ███╗   ║
            ║   ██╔══██╗██╔══██╗██╔════╝    ██╔════╝██╔═══██╗████╗ ████║   ║
            ║   ██████╔╝██║  ██║█████╗      ██║     ██║   ██║██╔████╔██║   ║
            ║   ██╔═══╝ ██║  ██║██╔══╝      ██║     ██║   ██║██║╚██╔╝██║   ║
            ║   ██║     ██████╔╝██║         ╚██████╗╚██████╔╝██║ ╚═╝ ██║   ║
            ║   ╚═╝     ╚═════╝ ╚═╝          ╚═════╝ ╚═════╝ ╚═╝     ╚═╝   ║
            ║                                                               ║
            %s
            ║                   Powered by Spring Boot                      ║
            ║                                                               ║
            ╚═══════════════════════════════════════════════════════════════╝
            """;

    private static final String BANNER = BANNER_TEMPLATE.formatted(
            centerInBox("Modern PDF Compression Pipeline v" + resolveVersion()));

    /**
     * Version of the running build, so the banner can never drift from pom.xml (the single
     * source of truth). {@code spring-boot-maven-plugin} writes {@code Implementation-Version}
     * (= pom.xml {@code <version>}) into the jar manifest at package time.
     *
     * <p>Falls back to {@code "dev"} — never {@code null} — when there is no manifest, i.e.
     * when running from an IDE or from exploded classes.
     */
    private static String resolveVersion() {
        // Plain jar / exploded-with-manifest: the JVM attaches the manifest to the package.
        // In a Spring Boot fat jar the classes live in BOOT-INF/classes and this is null,
        // so fall through to reading the jar manifest ourselves.
        String version = SquishApplication.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = versionFromBootManifest();
        }
        return (version == null || version.isBlank()) ? "dev" : version;
    }

    /**
     * Reads {@code Implementation-Version} from the fat jar's own manifest. Every jar on the
     * classpath has a {@code META-INF/MANIFEST.MF}, so we identify ours by its {@code Start-Class}
     * — an attribute only the repackaged Squish jar carries — rather than trusting iteration order.
     */
    private static String versionFromBootManifest() {
        try {
            var manifests = SquishApplication.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
            while (manifests.hasMoreElements()) {
                try (var in = manifests.nextElement().openStream()) {
                    var attributes = new java.util.jar.Manifest(in).getMainAttributes();
                    if (SquishApplication.class.getName().equals(attributes.getValue("Start-Class"))) {
                        return attributes.getValue("Implementation-Version");
                    }
                }
            }
        } catch (Exception e) {
            // Version is cosmetic: never let banner rendering break startup.
        }
        return null;
    }

    /** Centers {@code text} inside the fixed-width banner box, keeping the borders aligned. */
    private static String centerInBox(String text) {
        String content = text.length() > BANNER_WIDTH ? text.substring(0, BANNER_WIDTH) : text;
        int padding = BANNER_WIDTH - content.length();
        int left = padding / 2;
        return "║" + " ".repeat(left) + content + " ".repeat(padding - left) + "║";
    }

    private final SquishProperties properties;
    private final ProgressTracker tracker;
    private final CompressionPipeline pipeline;
    private final WatchdogService watchdogService;
    private final ConfigurableApplicationContext applicationContext;

    @Autowired(required = false)
    private EmailService emailService;

    public SquishApplication(
            SquishProperties properties,
            ProgressTracker tracker,
            CompressionPipeline pipeline,
            WatchdogService watchdogService,
            ConfigurableApplicationContext applicationContext) {
        this.properties = properties;
        this.tracker = tracker;
        this.pipeline = pipeline;
        this.watchdogService = watchdogService;
        this.applicationContext = applicationContext;
    }

    public static void main(String[] args) {
        System.out.println(BANNER);
        SpringApplication.run(SquishApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("Starting PDF Compressor Modern");
        log.info("Mode: {} | Threads: {} | Dry-run: {} | Watch: {}",
                properties.getMode(),
                properties.getPipeline().getWorkerThreads(),
                properties.isDryRun(),
                properties.getWatchdog().isEnabled());

        // The web server (dashboard/API) is now started and managed by Spring.

        if (properties.getWatchdog().isEnabled()) {
            // Watchdog stays alive until a shutdown signal (waits on its own latch).
            runWatchdogMode();
        } else {
            runBatchMode();
            // Embedded Tomcat keeps the JVM alive after batch work, so exit
            // explicitly to preserve the original batch "run once and stop" behaviour.
            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
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
            var reportPath = ReportGenerator.generate(tracker, properties, "squish_report");
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
            var reportPath = ReportGenerator.generate(tracker, properties, "squish_watchdog_report");
            if (reportPath != null) {
                log.info("Report saved to: {}", reportPath.toAbsolutePath());
            }

            printWatchdogSummary();

        } finally {
            watchdogService.close();
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
            if (tracker.isFailedRecordsTruncated()) {
                System.out.printf("  (showing first %d of %d failures)%n",
                        ProgressTracker.MAX_FAILED_RECORDS, tracker.failedCount());
            }
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
