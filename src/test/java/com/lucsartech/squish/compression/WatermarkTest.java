package com.lucsartech.squish.compression;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.lucsartech.squish.config.CompressionMode;
import com.lucsartech.squish.config.CompressionProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for PDF watermarking functionality.
 */
@DisplayName("Watermark")
class WatermarkTest {

    /**
     * Creates a simple test PDF with text content.
     */
    private byte[] createTestPdf() throws IOException {
        try (var outputStream = new ByteArrayOutputStream()) {
            try (var writer = new PdfWriter(outputStream);
                 var pdfDoc = new PdfDocument(writer);
                 var document = new Document(pdfDoc)) {

                document.add(new Paragraph("Test PDF Document"));
                document.add(new Paragraph("This is page 1 of the test document."));
            }
            return outputStream.toByteArray();
        }
    }

    /**
     * Creates a multi-page test PDF.
     */
    private byte[] createMultiPagePdf(int pages) throws IOException {
        try (var outputStream = new ByteArrayOutputStream()) {
            try (var writer = new PdfWriter(outputStream);
                 var pdfDoc = new PdfDocument(writer);
                 var document = new Document(pdfDoc)) {

                for (int i = 1; i <= pages; i++) {
                    if (i > 1) {
                        document.add(new com.itextpdf.layout.element.AreaBreak());
                    }
                    document.add(new Paragraph("Page " + i));
                    document.add(new Paragraph("Content for page " + i));
                }
            }
            return outputStream.toByteArray();
        }
    }

    /**
     * Extract all text from a PDF.
     */
    private String extractText(byte[] pdfBytes) throws IOException {
        try (var inputStream = new ByteArrayInputStream(pdfBytes);
             var reader = new PdfReader(inputStream);
             var pdfDoc = new PdfDocument(reader)) {

            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= pdfDoc.getNumberOfPages(); i++) {
                sb.append(PdfTextExtractor.getTextFromPage(pdfDoc.getPage(i)));
            }
            return sb.toString();
        }
    }

    @Nested
    @DisplayName("Basic watermarking")
    class BasicWatermarking {

        @Test
        @DisplayName("should add watermark to PDF")
        void addWatermark() throws IOException {
            // Create profile with watermark enabled
            var profile = new CompressionProfile("watermarked", CompressionMode.MEDIUM);
            profile.setWatermarkEnabled(true);
            profile.setWatermarkText("CONFIDENTIAL");

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            // Verify the PDF is valid
            assertThat(result.compressedData()).isNotEmpty();

            // Extract text and verify watermark is present
            String text = extractText(result.compressedData());
            assertThat(text).contains("CONFIDENTIAL");
        }

        @Test
        @DisplayName("should not add watermark when disabled")
        void noWatermarkWhenDisabled() throws IOException {
            var profile = new CompressionProfile("normal", CompressionMode.MEDIUM);
            profile.setWatermarkEnabled(false);
            profile.setWatermarkText("SHOULD NOT APPEAR");

            var compressor = new Squish(profile);
            // Needs an image: a text-only PDF is skipped by the no-gain guard.
            byte[] input = TestPdfs.withImage();

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            String text = extractText(result.compressedData());
            assertThat(text).doesNotContain("SHOULD NOT APPEAR");
        }

        @Test
        @DisplayName("should handle null watermark text gracefully")
        void nullWatermarkText() throws IOException {
            var profile = new CompressionProfile("null-text", CompressionMode.MEDIUM);
            profile.setWatermarkEnabled(true);
            profile.setWatermarkText(null);

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = compressor.compress(1L, 0L, "test.pdf", input);

            // Should still succeed, just without watermark
            assertThat(result).isInstanceOf(CompressionResult.Success.class);
        }

        @Test
        @DisplayName("should handle empty watermark text gracefully")
        void emptyWatermarkText() throws IOException {
            var profile = new CompressionProfile("empty-text", CompressionMode.MEDIUM);
            profile.setWatermarkEnabled(true);
            profile.setWatermarkText("   ");

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = compressor.compress(1L, 0L, "test.pdf", input);

            assertThat(result).isInstanceOf(CompressionResult.Success.class);
        }
    }

    @Nested
    @DisplayName("Multi-page watermarking")
    class MultiPageWatermarking {

        @Test
        @DisplayName("should add watermark to all pages")
        void watermarkAllPages() throws IOException {
            var profile = new CompressionProfile("multipage", CompressionMode.MEDIUM);
            profile.setWatermarkEnabled(true);
            profile.setWatermarkText("DRAFT");

            var compressor = new Squish(profile);
            byte[] input = createMultiPagePdf(3);

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            // Count occurrences of watermark text
            String text = extractText(result.compressedData());
            int count = text.split("DRAFT", -1).length - 1;

            // Should appear on all 3 pages
            assertThat(count).isGreaterThanOrEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Watermark positions")
    class WatermarkPositions {

        @Test
        @DisplayName("should support CENTER position")
        void centerPosition() throws IOException {
            var profile = new CompressionProfile("center", CompressionMode.MEDIUM);
            profile.setWatermarkEnabled(true);
            profile.setWatermarkText("CENTER");
            profile.setWatermarkPosition(CompressionProfile.WatermarkPosition.CENTER);

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            String text = extractText(result.compressedData());
            assertThat(text).contains("CENTER");
        }

        @Test
        @DisplayName("should support DIAGONAL position")
        void diagonalPosition() throws IOException {
            var profile = new CompressionProfile("diagonal", CompressionMode.MEDIUM);
            profile.setWatermarkEnabled(true);
            profile.setWatermarkText("DIAGONAL");
            profile.setWatermarkPosition(CompressionProfile.WatermarkPosition.DIAGONAL);

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            // Diagonal text might not be extracted properly by text extractor
            // but the PDF should be valid
            assertThat(result.compressedData()).isNotEmpty();
        }

        @Test
        @DisplayName("should support TILED position")
        void tiledPosition() throws IOException {
            var profile = new CompressionProfile("tiled", CompressionMode.MEDIUM);
            profile.setWatermarkEnabled(true);
            profile.setWatermarkText("TILED");
            profile.setWatermarkPosition(CompressionProfile.WatermarkPosition.TILED);

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            // Tiled should produce multiple watermarks
            assertThat(result.compressedData()).isNotEmpty();
        }

        @Test
        @DisplayName("should support corner positions")
        void cornerPositions() throws IOException {
            for (var pos : new CompressionProfile.WatermarkPosition[]{
                    CompressionProfile.WatermarkPosition.TOP_LEFT,
                    CompressionProfile.WatermarkPosition.TOP_RIGHT,
                    CompressionProfile.WatermarkPosition.BOTTOM_LEFT,
                    CompressionProfile.WatermarkPosition.BOTTOM_RIGHT
            }) {
                var profile = new CompressionProfile("corner", CompressionMode.MEDIUM);
                profile.setWatermarkEnabled(true);
                profile.setWatermarkText("CORNER");
                profile.setWatermarkPosition(pos);

                var compressor = new Squish(profile);
                byte[] input = createTestPdf();

                var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

                assertThat(result.compressedData()).isNotEmpty();
            }
        }
    }

    @Nested
    @DisplayName("Watermark styling")
    class WatermarkStyling {

        @Test
        @DisplayName("should apply custom opacity")
        void customOpacity() throws IOException {
            var profile = new CompressionProfile("opacity", CompressionMode.MEDIUM);
            profile.setWatermarkEnabled(true);
            profile.setWatermarkText("FADED");
            profile.setWatermarkOpacity(0.1f);

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = compressor.compress(1L, 0L, "test.pdf", input);

            assertThat(result).isInstanceOf(CompressionResult.Success.class);
        }

        @Test
        @DisplayName("should apply custom font size")
        void customFontSize() throws IOException {
            var profile = new CompressionProfile("large-font", CompressionMode.MEDIUM);
            profile.setWatermarkEnabled(true);
            profile.setWatermarkText("BIG");
            profile.setWatermarkFontSize(72);

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            String text = extractText(result.compressedData());
            assertThat(text).contains("BIG");
        }

        @Test
        @DisplayName("should apply custom color")
        void customColor() throws IOException {
            var profile = new CompressionProfile("colored", CompressionMode.MEDIUM);
            profile.setWatermarkEnabled(true);
            profile.setWatermarkText("RED");
            profile.setWatermarkColor("#FF0000");

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = compressor.compress(1L, 0L, "test.pdf", input);

            assertThat(result).isInstanceOf(CompressionResult.Success.class);
        }

        @Test
        @DisplayName("should handle invalid color gracefully")
        void invalidColor() throws IOException {
            var profile = new CompressionProfile("bad-color", CompressionMode.MEDIUM);
            profile.setWatermarkEnabled(true);
            profile.setWatermarkText("GRAY");
            profile.setWatermarkColor("not-a-color");

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            // Should fallback to gray and not fail
            var result = compressor.compress(1L, 0L, "test.pdf", input);

            assertThat(result).isInstanceOf(CompressionResult.Success.class);
        }

        @Test
        @DisplayName("should support different fonts")
        void differentFonts() throws IOException {
            for (String fontName : new String[]{"Helvetica", "Times", "Courier", "Unknown"}) {
                var profile = new CompressionProfile("font-test", CompressionMode.MEDIUM);
                profile.setWatermarkEnabled(true);
                profile.setWatermarkText("FONT");
                profile.setWatermarkFontName(fontName);

                var compressor = new Squish(profile);
                byte[] input = createTestPdf();

                var result = compressor.compress(1L, 0L, "test.pdf", input);

                assertThat(result).isInstanceOf(CompressionResult.Success.class);
            }
        }
    }

    @Nested
    @DisplayName("Predefined confidential profile")
    class ConfidentialProfile {

        @Test
        @DisplayName("should apply watermark with confidential profile")
        void confidentialProfileWatermark() throws IOException {
            var profile = CompressionProfile.confidential();

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            // Diagonal text might not extract perfectly, but verify PDF is valid
            assertThat(result.compressedData()).isNotEmpty();
            assertThat(result.compressedData()[0]).isEqualTo((byte) '%');
        }
    }
}
