package com.lucsartech.squish.compression;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
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
 * Tests for PDF/A compliance functionality.
 */
@DisplayName("PDF/A Compliance")
class PdfATest {

    /**
     * Creates a simple test PDF with text content.
     */
    private byte[] createTestPdf() throws IOException {
        try (var outputStream = new ByteArrayOutputStream()) {
            try (var writer = new PdfWriter(outputStream);
                 var pdfDoc = new PdfDocument(writer);
                 var document = new Document(pdfDoc)) {

                document.add(new Paragraph("Test PDF Document"));
                document.add(new Paragraph("This is a test document for PDF/A conversion."));
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
     * Check if a PDF contains PDF/A markers.
     * This is a simple check - full validation would require a PDF/A validator.
     */
    private boolean containsPdfAMarkers(byte[] pdfBytes) throws IOException {
        try (var inputStream = new ByteArrayInputStream(pdfBytes);
             var reader = new PdfReader(inputStream);
             var pdfDoc = new PdfDocument(reader)) {

            // Check for output intent (required for PDF/A)
            var catalog = pdfDoc.getCatalog();
            var outputIntents = catalog.getPdfObject().getAsArray(
                    com.itextpdf.kernel.pdf.PdfName.OutputIntents);

            return outputIntents != null && outputIntents.size() > 0;
        }
    }

    /**
     * Get document info from PDF.
     */
    private String getProducer(byte[] pdfBytes) throws IOException {
        try (var inputStream = new ByteArrayInputStream(pdfBytes);
             var reader = new PdfReader(inputStream);
             var pdfDoc = new PdfDocument(reader)) {

            return pdfDoc.getDocumentInfo().getProducer();
        }
    }

    @Nested
    @DisplayName("PDF/A-1b conversion")
    class PdfA1bConversion {

        @Test
        @DisplayName("should convert PDF to PDF/A-1b")
        void convertToPdfA1b() throws IOException {
            var profile = new CompressionProfile("pdfa-1b", CompressionMode.LOSSLESS);
            profile.setPdfaEnabled(true);
            profile.setPdfaConformance(CompressionProfile.PdfAConformance.PDF_A_1B);

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            assertThat(result.compressedData()).isNotEmpty();
            assertThat(containsPdfAMarkers(result.compressedData())).isTrue();
        }
    }

    @Nested
    @DisplayName("PDF/A-2b conversion")
    class PdfA2bConversion {

        @Test
        @DisplayName("should convert PDF to PDF/A-2b")
        void convertToPdfA2b() throws IOException {
            var profile = new CompressionProfile("pdfa-2b", CompressionMode.LOSSLESS);
            profile.setPdfaEnabled(true);
            profile.setPdfaConformance(CompressionProfile.PdfAConformance.PDF_A_2B);

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            assertThat(result.compressedData()).isNotEmpty();
            assertThat(containsPdfAMarkers(result.compressedData())).isTrue();
        }

        @Test
        @DisplayName("should preserve page count during conversion")
        void preservePageCount() throws IOException {
            var profile = new CompressionProfile("pdfa-2b", CompressionMode.LOSSLESS);
            profile.setPdfaEnabled(true);
            profile.setPdfaConformance(CompressionProfile.PdfAConformance.PDF_A_2B);

            var compressor = new Squish(profile);
            byte[] input = createMultiPagePdf(5);

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            // Verify page count is preserved
            try (var inputStream = new ByteArrayInputStream(result.compressedData());
                 var reader = new PdfReader(inputStream);
                 var pdfDoc = new PdfDocument(reader)) {
                assertThat(pdfDoc.getNumberOfPages()).isEqualTo(5);
            }
        }
    }

    @Nested
    @DisplayName("PDF/A-3b conversion")
    class PdfA3bConversion {

        @Test
        @DisplayName("should convert PDF to PDF/A-3b")
        void convertToPdfA3b() throws IOException {
            var profile = new CompressionProfile("pdfa-3b", CompressionMode.LOSSLESS);
            profile.setPdfaEnabled(true);
            profile.setPdfaConformance(CompressionProfile.PdfAConformance.PDF_A_3B);

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            assertThat(result.compressedData()).isNotEmpty();
            assertThat(containsPdfAMarkers(result.compressedData())).isTrue();
        }
    }

    @Nested
    @DisplayName("PDF/A with compression")
    class PdfAWithCompression {

        @Test
        @DisplayName("should apply compression before PDF/A conversion")
        void compressionBeforePdfA() throws IOException {
            var profile = new CompressionProfile("pdfa-compressed", CompressionMode.MEDIUM);
            profile.setPdfaEnabled(true);
            profile.setPdfaConformance(CompressionProfile.PdfAConformance.PDF_A_2B);

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            assertThat(result.compressedData()).isNotEmpty();
            assertThat(containsPdfAMarkers(result.compressedData())).isTrue();
        }

        @Test
        @DisplayName("should apply watermark with PDF/A conversion")
        void watermarkWithPdfA() throws IOException {
            var profile = new CompressionProfile("pdfa-watermarked", CompressionMode.MEDIUM);
            profile.setPdfaEnabled(true);
            profile.setPdfaConformance(CompressionProfile.PdfAConformance.PDF_A_2B);
            profile.setWatermarkEnabled(true);
            profile.setWatermarkText("ARCHIVED");

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            assertThat(result.compressedData()).isNotEmpty();
            assertThat(containsPdfAMarkers(result.compressedData())).isTrue();
        }
    }

    @Nested
    @DisplayName("PDF/A metadata")
    class PdfAMetadata {

        @Test
        @DisplayName("should set Squish as producer")
        void squishProducer() throws IOException {
            var profile = new CompressionProfile("pdfa", CompressionMode.LOSSLESS);
            profile.setPdfaEnabled(true);

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            String producer = getProducer(result.compressedData());
            assertThat(producer).contains("Squish");
        }
    }

    @Nested
    @DisplayName("Archival profile")
    class ArchivalProfile {

        @Test
        @DisplayName("should use archival predefined profile")
        void archivalPreset() throws IOException {
            var profile = CompressionProfile.archival();

            var compressor = new Squish(profile);
            byte[] input = createTestPdf();

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            assertThat(result.compressedData()).isNotEmpty();
            assertThat(containsPdfAMarkers(result.compressedData())).isTrue();
        }

        @Test
        @DisplayName("archival profile should be lossless")
        void archivalIsLossless() {
            var profile = CompressionProfile.archival();

            assertThat(profile.isLossless()).isTrue();
            assertThat(profile.isPdfaEnabled()).isTrue();
            assertThat(profile.getPdfaConformance())
                    .isEqualTo(CompressionProfile.PdfAConformance.PDF_A_2B);
        }
    }

    @Nested
    @DisplayName("PDF/A disabled")
    class PdfADisabled {

        @Test
        @DisplayName("should not convert when PDF/A is disabled")
        void noConversionWhenDisabled() throws IOException {
            var profile = new CompressionProfile("normal", CompressionMode.MEDIUM);
            profile.setPdfaEnabled(false);

            var compressor = new Squish(profile);
            // Needs an image: a text-only PDF is skipped by the no-gain guard.
            byte[] input = TestPdfs.withImage();

            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", input);

            // Should still produce valid PDF
            assertThat(result.compressedData()).isNotEmpty();

            // Should not have PDF/A output intent
            assertThat(containsPdfAMarkers(result.compressedData())).isFalse();
        }
    }
}
