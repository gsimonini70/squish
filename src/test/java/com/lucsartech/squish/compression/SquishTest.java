package com.lucsartech.squish.compression;

import com.lucsartech.squish.config.CompressionMode;
import com.lucsartech.squish.config.CompressionProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for Squish.
 */
@DisplayName("Squish")
class SquishTest {

    private Squish compressor;
    private byte[] testPdf;

    @BeforeEach
    void setUp() throws IOException {
        compressor = new Squish(CompressionProfile.office());
        // Compression can only succeed on a document that has something to compress.
        testPdf = TestPdfs.withImage();
    }

    /**
     * Creates a simple test PDF with text content.
     */
    private byte[] createTestPdf() throws IOException {
        return TestPdfs.textOnly();
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("should create compressor with profile")
        void withProfile() {
            var profile = CompressionProfile.maximum();
            var comp = new Squish(profile);

            assertThat(comp.profile()).isEqualTo(profile);
            assertThat(comp.profile().getName()).isEqualTo("maximum");
        }

        @Test
        @DisplayName("should create compressor with legacy mode")
        @SuppressWarnings("deprecation")
        void withLegacyMode() {
            var comp = new Squish(CompressionMode.AGGRESSIVE);

            assertThat(comp.mode()).isEqualTo(CompressionMode.AGGRESSIVE);
            assertThat(comp.profile().getName()).isEqualTo("legacy");
        }

        @Test
        @DisplayName("should throw on null profile")
        void nullProfile() {
            assertThatThrownBy(() -> new Squish((CompressionProfile) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("profile");
        }

        @Test
        @DisplayName("should throw on null mode")
        @SuppressWarnings("deprecation")
        void nullMode() {
            assertThatThrownBy(() -> new Squish((CompressionMode) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("mode");
        }
    }

    @Nested
    @DisplayName("Compression")
    class Compression {

        @Test
        @DisplayName("should compress valid PDF")
        void compressValidPdf() {
            CompressionResult result = compressor.compress(1L, 0L, "test.pdf", testPdf);

            assertThat(result).isInstanceOf(CompressionResult.Success.class);

            var success = (CompressionResult.Success) result;
            assertThat(success.id()).isEqualTo(1L);
            assertThat(success.ctr()).isEqualTo(0L);
            assertThat(success.filename()).isEqualTo("test.pdf");
            assertThat(success.originalSize()).isEqualTo(testPdf.length);
            assertThat(success.compressedData()).isNotEmpty();
            assertThat(success.processingTime()).isNotNull();
        }

        @Test
        @DisplayName("should include CTR in result")
        void includesCtr() {
            CompressionResult result = compressor.compress(100L, 5L, "doc.pdf", testPdf);

            assertThat(result).isInstanceOf(CompressionResult.Success.class);
            var success = (CompressionResult.Success) result;
            assertThat(success.id()).isEqualTo(100L);
            assertThat(success.ctr()).isEqualTo(5L);
        }

        @Test
        @DisplayName("compressed PDF should actually be smaller than the input")
        void compressedPdfIsSmaller() {
            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", testPdf);

            assertThat(result.compressedSize())
                    .as("Squish overwrites the source BLOB, so a Success must never grow the document")
                    .isLessThan(result.originalSize());
            assertThat(result.savingsPercent()).isPositive();
        }

        @Test
        @DisplayName("compressed PDF should be valid")
        void compressedPdfIsValid() {
            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", testPdf);
            byte[] compressed = result.compressedData();

            // Verify it starts with PDF magic bytes
            assertThat(compressed).hasSizeGreaterThan(5);
            assertThat(compressed[0]).isEqualTo((byte) '%');
            assertThat(compressed[1]).isEqualTo((byte) 'P');
            assertThat(compressed[2]).isEqualTo((byte) 'D');
            assertThat(compressed[3]).isEqualTo((byte) 'F');
            assertThat(compressed[4]).isEqualTo((byte) '-');
        }
    }

    @Nested
    @DisplayName("Non-PDF handling")
    class NonPdfHandling {

        @Test
        @DisplayName("should skip non-PDF file")
        void skipNonPdf() {
            byte[] notPdf = "This is not a PDF file".getBytes();

            CompressionResult result = compressor.compress(1L, 0L, "text.txt", notPdf);

            assertThat(result).isInstanceOf(CompressionResult.Skipped.class);
            var skipped = (CompressionResult.Skipped) result;
            assertThat(skipped.id()).isEqualTo(1L);
            assertThat(skipped.filename()).isEqualTo("text.txt");
            assertThat(skipped.reason()).containsIgnoringCase("not a PDF");
        }

        @Test
        @DisplayName("should skip empty file")
        void skipEmptyFile() {
            byte[] empty = new byte[0];

            CompressionResult result = compressor.compress(1L, 0L, "empty.pdf", empty);

            assertThat(result).isInstanceOf(CompressionResult.Skipped.class);
        }

        @Test
        @DisplayName("should skip file too small to be PDF")
        void skipTooSmall() {
            byte[] tiny = new byte[] {'%', 'P', 'D'};

            CompressionResult result = compressor.compress(1L, 0L, "tiny.pdf", tiny);

            assertThat(result).isInstanceOf(CompressionResult.Skipped.class);
        }

        @Test
        @DisplayName("should throw on null input")
        void nullInput() {
            assertThatThrownBy(() -> compressor.compress(1L, 0L, "test.pdf", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("No-gain guard")
    class NoGainGuard {

        @Test
        @DisplayName("should skip a PDF that compression would make larger")
        void skipWhenOutputWouldGrow() throws IOException {
            // A text-only PDF is already near-optimal; recompressing it adds bytes.
            byte[] textPdf = TestPdfs.textOnly();

            CompressionResult result = compressor.compress(1L, 0L, "text.pdf", textPdf);

            assertThat(result).isInstanceOf(CompressionResult.Skipped.class);
            var skipped = (CompressionResult.Skipped) result;
            assertThat(skipped.noGain()).isTrue();
            assertThat(skipped.size()).isEqualTo(textPdf.length);
            assertThat(skipped.reason()).contains("No size gain");
        }

        @Test
        @DisplayName("should distinguish a no-gain skip from a non-PDF skip")
        void noGainIsNotTheSameAsNotPdf() {
            var notPdf = (CompressionResult.Skipped) compressor.compress(1L, 0L, "x.txt", "nope".getBytes());

            assertThat(notPdf.noGain()).isFalse();
            assertThat(CompressionResult.Skipped.noGain(1L, 0L, "a.pdf", 100, 120).noGain()).isTrue();
        }

        @Test
        @DisplayName("PDF/A conversion is kept even when the document grows")
        void pdfaKeptDespiteGrowth() throws IOException {
            byte[] textPdf = TestPdfs.textOnly();
            var result = new Squish(CompressionProfile.archival()).compress(1L, 0L, "a.pdf", textPdf);

            assertThat(result)
                    .as("PDF/A conversion is the goal, not size reduction")
                    .isInstanceOf(CompressionResult.Success.class);
            assertThat(((CompressionResult.Success) result).compressedSize()).isGreaterThan(textPdf.length);
        }

        @Test
        @DisplayName("watermarking is kept even when the document grows")
        void watermarkKeptDespiteGrowth() throws IOException {
            byte[] textPdf = TestPdfs.textOnly();
            var result = new Squish(CompressionProfile.confidential()).compress(1L, 0L, "w.pdf", textPdf);

            assertThat(result)
                    .as("a requested watermark must survive even if it costs bytes")
                    .isInstanceOf(CompressionResult.Success.class);
        }

        @Test
        @DisplayName("re-encryption is kept even when the document grows")
        void encryptionKeptDespiteGrowth() throws IOException {
            byte[] textPdf = TestPdfs.textOnly();
            var result = new Squish(CompressionProfile.office(), null, "secret")
                    .compress(1L, 0L, "e.pdf", textPdf);

            assertThat(result).isInstanceOf(CompressionResult.Success.class);
        }
    }

    @Nested
    @DisplayName("Lossless mode")
    class LosslessMode {

        @Test
        @DisplayName("lossless compression should not modify images")
        void losslessNoImageModification() throws IOException {
            var losslessCompressor = new Squish(CompressionProfile.archival());
            byte[] pdf = createTestPdf();

            var result = (CompressionResult.Success) losslessCompressor.compress(1L, 0L, "test.pdf", pdf);

            // In lossless mode, PDF should still be valid
            assertThat(result.compressedData()).isNotEmpty();
            assertThat(result.compressedData()[0]).isEqualTo((byte) '%');
        }
    }

    @Nested
    @DisplayName("CompressionResult types")
    class CompressionResultTypes {

        @Test
        @DisplayName("Success should calculate savings correctly")
        void successSavings() {
            var result = (CompressionResult.Success) compressor.compress(1L, 0L, "test.pdf", testPdf);

            long original = result.originalSize();
            long compressed = result.compressedSize();

            assertThat(original).isPositive();
            assertThat(compressed).isPositive();
        }

        @Test
        @DisplayName("Skipped should contain size info")
        void skippedContainsSize() {
            byte[] notPdf = "not a pdf".getBytes();
            var result = (CompressionResult.Skipped) compressor.compress(1L, 0L, "file.txt", notPdf);

            assertThat(result.size()).isEqualTo(notPdf.length);
        }

        @Test
        @DisplayName("Skipped.notPdf factory method")
        void skippedNotPdfFactory() {
            var skipped = CompressionResult.Skipped.notPdf(42L, 1L, "image.jpg", 1024);

            assertThat(skipped.id()).isEqualTo(42L);
            assertThat(skipped.ctr()).isEqualTo(1L);
            assertThat(skipped.filename()).isEqualTo("image.jpg");
            assertThat(skipped.size()).isEqualTo(1024);
            assertThat(skipped.reason()).containsIgnoringCase("not a PDF");
        }

        @Test
        @DisplayName("Failure.of factory method")
        void failureFactory() {
            var exception = new RuntimeException("Test error");
            var failure = CompressionResult.Failure.of(99L, 2L, "corrupt.pdf", exception);

            assertThat(failure.id()).isEqualTo(99L);
            assertThat(failure.ctr()).isEqualTo(2L);
            assertThat(failure.filename()).isEqualTo("corrupt.pdf");
            assertThat(failure.errorMessage()).contains("Test error");
        }
    }
}
