package com.lucsartech.pdf.report;

import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import com.lucsartech.pdf.config.PdfCompressorProperties;
import com.lucsartech.pdf.pipeline.ProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * PDF report generator with modern, professional design.
 */
public final class ReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerator.class);

    // Color palette
    private static final DeviceRgb PRIMARY = new DeviceRgb(59, 130, 246);      // Blue
    private static final DeviceRgb SECONDARY = new DeviceRgb(139, 92, 246);    // Purple
    private static final DeviceRgb SUCCESS = new DeviceRgb(34, 197, 94);       // Green
    private static final DeviceRgb WARNING = new DeviceRgb(245, 158, 11);      // Orange
    private static final DeviceRgb DANGER = new DeviceRgb(239, 68, 68);        // Red
    private static final DeviceRgb DARK = new DeviceRgb(15, 23, 42);           // Slate 900
    private static final DeviceRgb LIGHT = new DeviceRgb(241, 245, 249);       // Slate 100
    private static final DeviceRgb MUTED = new DeviceRgb(100, 116, 139);       // Slate 500

    private static final DateTimeFormatter DTF = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private ReportGenerator() {}

    /**
     * Generate a PDF report of the compression run.
     */
    public static Path generate(ProgressTracker tracker, PdfCompressorProperties properties, String baseName) {
        // Check if reports are enabled
        if (!properties.getReport().isEnabled()) {
            log.debug("Report generation is disabled");
            return null;
        }

        // Create report directory if it doesn't exist
        Path reportDir = Path.of(properties.getReport().getDirectory());
        try {
            Files.createDirectories(reportDir);
        } catch (IOException e) {
            log.error("Failed to create report directory: {}", reportDir, e);
            return null;
        }

        String filename = baseName + "_" + System.currentTimeMillis() + ".pdf";
        Path outputPath = reportDir.resolve(filename);

        try (var writer = new PdfWriter(new FileOutputStream(outputPath.toFile()));
             var pdf = new PdfDocument(writer);
             var doc = new Document(pdf, PageSize.A4)) {

            // Compact margins to fit on one page
            doc.setMargins(25, 30, 25, 30);

            addHeader(doc, properties);
            addExecutionSummary(doc, tracker, properties);
            addCompressionStats(doc, tracker);
            addProcessingSummary(doc, tracker);
            addThroughputStats(doc, tracker);

            if (!tracker.failedIds().isEmpty()) {
                addFailedRecords(doc, tracker);
            }

            addFooter(doc);

            log.info("Report generated: {}", outputPath.toAbsolutePath());

        } catch (Exception e) {
            log.error("Failed to generate report", e);
        }

        return outputPath;
    }

    private static void addHeader(Document doc, PdfCompressorProperties properties) {
        // Title with mode badge inline
        var modeText = properties.isDryRun() ? "DRY-RUN" : properties.getMode().name();

        var title = new Paragraph("Squish Report")
                .setFontSize(22)
                .setBold()
                .setFontColor(DARK)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginBottom(3);
        doc.add(title);

        // Mode badge - compact
        var modeColor = properties.isDryRun() ? WARNING : SUCCESS;
        var modeBadge = new Paragraph(modeText)
                .setFontSize(9)
                .setBold()
                .setFontColor(ColorConstants.WHITE)
                .setBackgroundColor(modeColor)
                .setPadding(4)
                .setPaddingLeft(12)
                .setPaddingRight(12)
                .setBorderRadius(new com.itextpdf.layout.properties.BorderRadius(12))
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginBottom(12);
        doc.add(modeBadge);
    }

    private static void addExecutionSummary(Document doc, ProgressTracker tracker, PdfCompressorProperties properties) {
        addSectionTitle(doc, "Execution Details");

        var table = new Table(UnitValue.createPercentArray(new float[]{1, 1.5f, 1, 1.5f}))
                .useAllAvailableWidth()
                .setMarginBottom(12);

        var pipeline = properties.getPipeline();
        String startStr = tracker.startTime() != null ? DTF.format(tracker.startTime()) : "N/A";
        String idRange = pipeline.getIdFrom() + " - " + (pipeline.hasUpperBound() ? pipeline.getIdTo() : "END");

        addInfoRow(table, "Mode", properties.getMode().name());
        addInfoRow(table, "Workers", String.valueOf(pipeline.getWorkerThreads()));
        addInfoRow(table, "ID Range", idRange);
        addInfoRow(table, "Duration", formatDuration(tracker.elapsedTime().toSeconds()));
        addInfoRow(table, "Started", startStr);
        addInfoRow(table, "Dry Run", properties.isDryRun() ? "Yes" : "No");

        doc.add(table);
    }

    private static void addCompressionStats(Document doc, ProgressTracker tracker) {
        addSectionTitle(doc, "Compression Statistics");

        var snapshot = tracker.snapshot();
        double initialDbMb = snapshot.initialDbSizeBytes() / 1024.0 / 1024.0;
        // Use currentDbSizeBytes (calculated) if finalDbSizeBytes is not set
        double actualDbMb = snapshot.finalDbSizeBytes() > 0
                ? snapshot.finalDbSizeBytes() / 1024.0 / 1024.0
                : snapshot.currentDbSizeBytes() / 1024.0 / 1024.0;
        double originalMb = snapshot.originalBytes() / 1024.0 / 1024.0;
        double compressedMb = snapshot.compressedBytes() / 1024.0 / 1024.0;

        var table = new Table(UnitValue.createPercentArray(new float[]{2, 1, 1, 1}))
                .useAllAvailableWidth()
                .setMarginBottom(12);

        // Header
        addHeaderCell(table, "Metric");
        addHeaderCell(table, "Before");
        addHeaderCell(table, "After");
        addHeaderCell(table, "Savings");

        // Database Size
        double savedMb = initialDbMb - actualDbMb;
        double savedPct = initialDbMb > 0 ? (savedMb / initialDbMb) * 100 : 0;

        addDataCell(table, "Database Size");
        addDataCell(table, String.format("%.2f MB", initialDbMb));
        addDataCell(table, String.format("%.2f MB", actualDbMb));
        addDataCell(table, String.format("-%.2f MB (%.1f%%)", savedMb, savedPct), SUCCESS);

        // Processed Data
        addDataCell(table, "Processed Data");
        addDataCell(table, String.format("%.2f MB", originalMb));
        addDataCell(table, String.format("%.2f MB", compressedMb));
        addDataCell(table, String.format("%.4f ratio", snapshot.compressionRatio()), PRIMARY);

        doc.add(table);
    }

    private static void addProcessingSummary(Document doc, ProgressTracker tracker) {
        addSectionTitle(doc, "Processing Summary");

        var table = new Table(UnitValue.createPercentArray(new float[]{1, 1, 1, 1}))
                .useAllAvailableWidth()
                .setMarginBottom(12);

        var snapshot = tracker.snapshot();

        addStatCard(table, "Total Records", String.valueOf(snapshot.totalRecords()), PRIMARY);
        addStatCard(table, "Processed", String.valueOf(snapshot.read()), PRIMARY);
        addStatCard(table, "Compressed", String.valueOf(snapshot.compressed()), SUCCESS);
        addStatCard(table, "Errors", String.valueOf(snapshot.errors()),
                snapshot.errors() > 0 ? DANGER : MUTED);

        doc.add(table);
    }

    private static void addThroughputStats(Document doc, ProgressTracker tracker) {
        addSectionTitle(doc, "Throughput");

        var table = new Table(UnitValue.createPercentArray(new float[]{1, 1, 1, 1}))
                .useAllAvailableWidth()
                .setMarginBottom(12);

        var snapshot = tracker.snapshot();

        addStatCard(table, "Records/sec", String.format("%.2f", snapshot.recordsPerSecond()), PRIMARY);
        addStatCard(table, "MB/sec", String.format("%.2f", snapshot.mbPerSecond()), SECONDARY);
        addStatCard(table, "Avg Time", snapshot.avgProcessingTimeMs() + " ms", SUCCESS);
        addStatCard(table, "Skipped", String.valueOf(snapshot.skipped()), MUTED);

        doc.add(table);
    }

    private static void addFailedRecords(Document doc, ProgressTracker tracker) {
        addSectionTitle(doc, "Failed Records (DLQ)", DANGER);

        var failedIds = tracker.failedIds();

        var text = new StringBuilder();
        int maxShow = Math.min(failedIds.size(), 50); // Limit to 50 IDs to save space
        for (int i = 0; i < maxShow; i++) {
            if (i > 0) text.append(", ");
            if (i > 0 && i % 15 == 0) text.append("\n");
            text.append(failedIds.get(i));
        }
        if (failedIds.size() > maxShow) {
            text.append(" ... and ").append(failedIds.size() - maxShow).append(" more");
        }

        var para = new Paragraph(text.toString())
                .setFontSize(8)
                .setFontColor(MUTED)
                .setBackgroundColor(new DeviceRgb(254, 242, 242))
                .setPadding(8)
                .setBorderRadius(new com.itextpdf.layout.properties.BorderRadius(6))
                .setMarginBottom(10);
        doc.add(para);
    }

    private static void addFooter(Document doc) {
        var footer = new Paragraph("Squish v2.0 | Built with Virtual Threads")
                .setFontSize(8)
                .setFontColor(MUTED)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginTop(15);
        doc.add(footer);

        var credits = new Paragraph("Designed & Engineered by Lucsartech Srl")
                .setFontSize(7)
                .setFontColor(new DeviceRgb(148, 163, 184))
                .setTextAlignment(TextAlignment.CENTER);
        doc.add(credits);
    }

    // ========== Helper Methods ==========

    private static void addSectionTitle(Document doc, String title) {
        addSectionTitle(doc, title, DARK);
    }

    private static void addSectionTitle(Document doc, String title, DeviceRgb color) {
        var para = new Paragraph(title)
                .setFontSize(11)
                .setBold()
                .setFontColor(color)
                .setMarginTop(8)
                .setMarginBottom(6);
        doc.add(para);
    }

    private static void addInfoRow(Table table, String label, String value) {
        table.addCell(new Cell()
                .add(new Paragraph(label).setFontSize(9).setBold().setFontColor(MUTED))
                .setBackgroundColor(LIGHT)
                .setBorder(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(new DeviceRgb(226, 232, 240), 1))
                .setPadding(6));

        table.addCell(new Cell()
                .add(new Paragraph(value).setFontSize(9).setFontColor(DARK))
                .setBorder(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(new DeviceRgb(226, 232, 240), 1))
                .setPadding(6));
    }

    private static void addHeaderCell(Table table, String text) {
        table.addHeaderCell(new Cell()
                .add(new Paragraph(text).setFontSize(9).setBold().setFontColor(ColorConstants.WHITE))
                .setBackgroundColor(DARK)
                .setTextAlignment(TextAlignment.CENTER)
                .setPadding(6));
    }

    private static void addDataCell(Table table, String text) {
        addDataCell(table, text, DARK);
    }

    private static void addDataCell(Table table, String text, DeviceRgb color) {
        table.addCell(new Cell()
                .add(new Paragraph(text).setFontSize(9).setFontColor(color))
                .setTextAlignment(TextAlignment.CENTER)
                .setBorder(new SolidBorder(new DeviceRgb(226, 232, 240), 1))
                .setPadding(5));
    }

    private static void addStatCard(Table table, String label, String value, DeviceRgb color) {
        var cell = new Cell()
                .setBackgroundColor(new DeviceRgb(248, 250, 252))
                .setBorder(new SolidBorder(new DeviceRgb(226, 232, 240), 1))
                .setBorderRadius(new com.itextpdf.layout.properties.BorderRadius(6))
                .setPadding(8)
                .setTextAlignment(TextAlignment.CENTER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE);

        cell.add(new Paragraph(label)
                .setFontSize(8)
                .setFontColor(MUTED)
                .setMarginBottom(2));

        cell.add(new Paragraph(value)
                .setFontSize(14)
                .setBold()
                .setFontColor(color));

        table.addCell(cell);
    }

    private static String formatDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0
                ? String.format("%dh %02dm %02ds", h, m, s)
                : String.format("%dm %02ds", m, s);
    }
}
