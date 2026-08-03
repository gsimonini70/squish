package com.lucsartech.squish.report;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.lucsartech.squish.config.CompressionMode;
import com.lucsartech.squish.config.SquishProperties;
import com.lucsartech.squish.pipeline.ProgressTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for {@link ReportGenerator#generate}.
 *
 * <p>The defect: on a mid-build failure the exception was logged and the method fell
 * through and returned {@code outputPath} anyway. The FileOutputStream had already
 * created and partially filled that file, so callers got back a path to a truncated,
 * structurally invalid PDF. {@code WatchdogService.sendCycleReport()} hands that path
 * straight to {@code EmailService}, whose only guard is
 * {@code reportPath != null && Files.exists(reportPath)} -- so the corrupt PDF was
 * emailed to the customer as their compression report.
 *
 * <p>No database and no network: {@code ProgressTracker} and {@code SquishProperties}
 * are plain objects built by hand (deliberately NOT {@code @SpringBootTest}, which would
 * open a HikariCP pool against Oracle).
 */
class ReportGeneratorTest {

    /** A tracker with realistic, non-zero state so the report actually has content to lay out. */
    private static ProgressTracker populatedTracker() {
        var tracker = new ProgressTracker();
        tracker.markStarted();
        tracker.setInitialStats(1_000L, 500L * 1024 * 1024);
        for (int i = 0; i < 5; i++) {
            tracker.recordRead();
            tracker.recordUpdate();
        }
        tracker.setFinalDbSize(400L * 1024 * 1024);
        tracker.markCompleted();
        return tracker;
    }

    private static SquishProperties propertiesFor(Path reportDir) {
        var properties = new SquishProperties();
        properties.setMode(CompressionMode.MEDIUM);
        properties.setDryRun(false);
        properties.getReport().setEnabled(true);
        properties.getReport().setDirectory(reportDir.toString());
        return properties;
    }

    private static List<Path> filesIn(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.toList();
        }
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("returns a path to a fully valid, parseable PDF")
        void producesValidPdf(@TempDir Path reportDir) throws IOException {
            Path result = ReportGenerator.generate(populatedTracker(), propertiesFor(reportDir), "test_report");

            assertThat(result).isNotNull();
            assertThat(result).exists();
            assertThat(Files.size(result)).isPositive();

            // The whole point: it must be a *valid* PDF, not merely a file that exists.
            // Re-open it with iText exactly the way a mail client / reader would.
            try (var pdf = new PdfDocument(new PdfReader(result.toFile()))) {
                assertThat(pdf.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("still produces a valid PDF when the DLQ section is rendered")
        void producesValidPdfWithFailedRecords(@TempDir Path reportDir) throws IOException {
            var tracker = populatedTracker();
            tracker.recordError(42L, new IllegalStateException("boom"));
            tracker.recordError(43L, new IllegalStateException("boom"));

            Path result = ReportGenerator.generate(tracker, propertiesFor(reportDir), "dlq_report");

            assertThat(result).isNotNull();
            try (var pdf = new PdfDocument(new PdfReader(result.toFile()))) {
                assertThat(pdf.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("creates the report directory if it does not exist yet")
        void createsMissingReportDirectory(@TempDir Path parent) throws IOException {
            Path reportDir = parent.resolve("nested/reports");
            assertThat(reportDir).doesNotExist();

            Path result = ReportGenerator.generate(populatedTracker(), propertiesFor(reportDir), "fresh");

            assertThat(result).isNotNull();
            try (var pdf = new PdfDocument(new PdfReader(result.toFile()))) {
                assertThat(pdf.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("failure path")
    class FailurePath {

        /**
         * Forces a real failure *inside* the try-with-resources body, i.e. after the
         * FileOutputStream has already created and started filling the output file.
         *
         * <p>{@code addHeader} evaluates {@code properties.getMode().name()} on the
         * non-dry-run branch. A null {@code mode} (a genuinely reachable misconfiguration --
         * {@code squish.mode} is bound from YAML and nothing validates it) throws an NPE
         * from deep inside the document build. This is a real, uninstrumented failure:
         * no mock, no test-only seam, no reflection.
         */
        private SquishProperties propertiesThatFailMidBuild(Path reportDir) {
            var properties = propertiesFor(reportDir);
            properties.setDryRun(false);
            properties.setMode(null);
            return properties;
        }

        @Test
        @DisplayName("returns null instead of a path to a truncated PDF")
        void returnsNullOnMidBuildFailure(@TempDir Path reportDir) {
            Path result = ReportGenerator.generate(
                    populatedTracker(), propertiesThatFailMidBuild(reportDir), "doomed");

            assertThat(result)
                    .as("a failed generation must not hand back a path -- EmailService would attach it")
                    .isNull();
        }

        @Test
        @DisplayName("leaves no partially-written file behind in the report directory")
        void deletesPartialFileOnMidBuildFailure(@TempDir Path reportDir) throws IOException {
            ReportGenerator.generate(populatedTracker(), propertiesThatFailMidBuild(reportDir), "doomed");

            assertThat(filesIn(reportDir))
                    .as("the truncated PDF must be cleaned up, not left to be picked up later")
                    .isEmpty();
        }

        @Test
        @DisplayName("a failed run does not corrupt a previously generated good report")
        void failureDoesNotDisturbEarlierReport(@TempDir Path reportDir) throws IOException {
            Path good = ReportGenerator.generate(populatedTracker(), propertiesFor(reportDir), "good");
            assertThat(good).isNotNull();

            ReportGenerator.generate(populatedTracker(), propertiesThatFailMidBuild(reportDir), "doomed");

            assertThat(filesIn(reportDir))
                    .as("only the good report survives")
                    .containsExactly(good);
            try (var pdf = new PdfDocument(new PdfReader(good.toFile()))) {
                assertThat(pdf.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("returns null when the output file cannot be opened for writing")
        void returnsNullWhenOutputNotWritable(@TempDir Path parent) throws IOException {
            // Report directory exists (so createDirectories succeeds) but is read-only,
            // so the FileOutputStream itself throws.
            Path reportDir = Files.createDirectory(parent.resolve("readonly"));
            assertThat(reportDir.toFile().setWritable(false)).isTrue();

            // Running as root would defeat the read-only bit; only assert if it really took.
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(reportDir));

            try {
                Path result = ReportGenerator.generate(populatedTracker(), propertiesFor(reportDir), "unwritable");

                assertThat(result).isNull();
                assertThat(filesIn(reportDir)).isEmpty();
            } finally {
                reportDir.toFile().setWritable(true);
            }
        }
    }

    @Nested
    @DisplayName("pre-existing null contract (must keep working)")
    class NullContract {

        @Test
        @DisplayName("returns null when reports are disabled")
        void returnsNullWhenDisabled(@TempDir Path reportDir) throws IOException {
            var properties = propertiesFor(reportDir);
            properties.getReport().setEnabled(false);

            Path result = ReportGenerator.generate(populatedTracker(), properties, "disabled");

            assertThat(result).isNull();
            assertThat(filesIn(reportDir)).isEmpty();
        }
    }
}
