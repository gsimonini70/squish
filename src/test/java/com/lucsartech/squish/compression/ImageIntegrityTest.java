package com.lucsartech.squish.compression;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfObject;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfStream;
import com.lucsartech.squish.config.CompressionMode;
import com.lucsartech.squish.config.CompressionProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for image XObject integrity.
 *
 * <p>Squish overwrites the source BLOB in place, so an image stream whose dictionary
 * disagrees with its encoded bytes is unrecoverable data corruption. Re-encoding an
 * image to JPEG changes its dimensions and may change its component count, and the
 * dictionary has to be updated to match.
 */
@DisplayName("Image XObject integrity")
class ImageIntegrityTest {

    /** The three fields of a JPEG SOFn marker that must agree with the PDF dictionary. */
    private record Sof(int width, int height, int components) {
    }

    /** Parses the SOFn frame header of a JPEG to read its true dimensions and channels. */
    private static Sof parseSof(byte[] jpeg) {
        int i = 2; // skip SOI
        while (i + 9 < jpeg.length) {
            if ((jpeg[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int marker = jpeg[i + 1] & 0xFF;
            int len = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            boolean isSof = marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (isSof) {
                int height = ((jpeg[i + 5] & 0xFF) << 8) | (jpeg[i + 6] & 0xFF);
                int width = ((jpeg[i + 7] & 0xFF) << 8) | (jpeg[i + 8] & 0xFF);
                int components = jpeg[i + 9] & 0xFF;
                return new Sof(width, height, components);
            }
            i += 2 + len;
        }
        return null;
    }

    private static List<PdfStream> imageStreams(PdfDocument pdf) {
        var images = new ArrayList<PdfStream>();
        for (int i = 1; i <= pdf.getNumberOfPdfObjects(); i++) {
            PdfObject obj = pdf.getPdfObject(i);
            if (obj instanceof PdfStream s && PdfName.Image.equals(s.get(PdfName.Subtype))) {
                images.add(s);
            }
        }
        return images;
    }

    private static int declaredComponents(PdfObject colorSpace) {
        if (PdfName.DeviceGray.equals(colorSpace)) return 1;
        if (PdfName.DeviceCMYK.equals(colorSpace)) return 4;
        return 3;
    }

    private static CompressionProfile aggressive() {
        return new CompressionProfile("test-aggressive", CompressionMode.AGGRESSIVE);
    }

    @Nested
    @DisplayName("Dictionary consistency after re-encoding")
    class DictionaryConsistency {

        @Test
        @DisplayName("RGB image: /Width and /Height match the JPEG frame header")
        void rgbDimensionsMatchJpeg() throws IOException {
            byte[] input = TestPdfs.withImage(400, 300, BufferedImage.TYPE_INT_RGB);
            var result = (CompressionResult.Success) new Squish(aggressive())
                    .compress(1L, 0L, "rgb.pdf", input);

            assertImagesConsistent(result.compressedData());
        }

        @Test
        @DisplayName("grayscale image: JPEG stays single-component, /ColorSpace stays /DeviceGray")
        void grayscaleStaysGrayscale() throws IOException {
            byte[] input = TestPdfs.withImage(400, 300, BufferedImage.TYPE_BYTE_GRAY);
            var result = (CompressionResult.Success) new Squish(aggressive())
                    .compress(1L, 0L, "gray.pdf", input);

            try (var pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(result.compressedData())))) {
                var images = imageStreams(pdf);
                assertThat(images).isNotEmpty();

                for (PdfStream image : images) {
                    if (!PdfName.DCTDecode.equals(image.get(PdfName.Filter))) {
                        continue; // left untouched because JPEG would have been larger
                    }
                    assertThat(image.get(PdfName.ColorSpace)).isEqualTo(PdfName.DeviceGray);

                    Sof sof = parseSof(image.getBytes(false));
                    assertThat(sof).isNotNull();
                    assertThat(sof.components())
                            .as("a /DeviceGray image must not be re-encoded as a 3-channel JPEG")
                            .isEqualTo(1);
                }
            }
            assertImagesConsistent(result.compressedData());
        }

        @Test
        @DisplayName("scaled image: dictionary reports the scaled size, not the original")
        void scaledDimensionsAreUpdated() throws IOException {
            byte[] input = TestPdfs.withImage(400, 300, BufferedImage.TYPE_INT_RGB);
            // AGGRESSIVE scales by 0.5, so a recompressed image must report 200x150.
            var result = (CompressionResult.Success) new Squish(aggressive())
                    .compress(1L, 0L, "scaled.pdf", input);

            try (var pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(result.compressedData())))) {
                var recompressed = imageStreams(pdf).stream()
                        .filter(s -> PdfName.DCTDecode.equals(s.get(PdfName.Filter)))
                        .toList();

                assertThat(recompressed).isNotEmpty();
                for (PdfStream image : recompressed) {
                    assertThat(image.getAsNumber(PdfName.Width).intValue()).isEqualTo(200);
                    assertThat(image.getAsNumber(PdfName.Height).intValue()).isEqualTo(150);
                }
            }
        }

        @Test
        @DisplayName("stale /DecodeParms from the previous filter are removed")
        void staleDecodeParmsRemoved() throws IOException {
            byte[] input = TestPdfs.withImage(400, 300, BufferedImage.TYPE_INT_RGB);
            var result = (CompressionResult.Success) new Squish(aggressive())
                    .compress(1L, 0L, "parms.pdf", input);

            try (var pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(result.compressedData())))) {
                for (PdfStream image : imageStreams(pdf)) {
                    if (PdfName.DCTDecode.equals(image.get(PdfName.Filter))) {
                        assertThat(image.get(PdfName.DecodeParms))
                                .as("DecodeParms describing the old FlateDecode filter must not survive")
                                .isNull();
                    }
                }
            }
        }

        /**
         * Every DCT-encoded image must have a dictionary that agrees with its JPEG frame
         * header on both dimensions and component count.
         */
        private void assertImagesConsistent(byte[] pdfBytes) throws IOException {
            try (var pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdfBytes)))) {
                var images = imageStreams(pdf);
                assertThat(images).isNotEmpty();

                for (PdfStream image : images) {
                    if (!PdfName.DCTDecode.equals(image.get(PdfName.Filter))) {
                        continue;
                    }
                    Sof sof = parseSof(image.getBytes(false));
                    assertThat(sof).as("DCTDecode stream must contain a parseable JPEG").isNotNull();

                    assertThat(image.getAsNumber(PdfName.Width).intValue())
                            .as("/Width must match the JPEG frame header")
                            .isEqualTo(sof.width());
                    assertThat(image.getAsNumber(PdfName.Height).intValue())
                            .as("/Height must match the JPEG frame header")
                            .isEqualTo(sof.height());
                    assertThat(declaredComponents(image.get(PdfName.ColorSpace)))
                            .as("/ColorSpace must match the JPEG component count")
                            .isEqualTo(sof.components());
                    assertThat(image.getAsNumber(PdfName.BitsPerComponent).intValue()).isEqualTo(8);
                }
            }
        }
    }

    @Nested
    @DisplayName("Lossless profiles")
    class LosslessProfiles {

        @Test
        @DisplayName("lossless leaves image streams untouched")
        void losslessDoesNotReencode() throws IOException {
            byte[] input = TestPdfs.withImage(400, 300, BufferedImage.TYPE_INT_RGB);
            var lossless = new CompressionProfile("test-lossless", CompressionMode.LOSSLESS);

            var result = new Squish(lossless).compress(1L, 0L, "lossless.pdf", input);
            byte[] output = result instanceof CompressionResult.Success s
                    ? s.compressedData()
                    : input;

            try (var pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(output)))) {
                for (PdfStream image : imageStreams(pdf)) {
                    assertThat(image.get(PdfName.Filter))
                            .as("lossless mode must not convert images to JPEG")
                            .isNotEqualTo(PdfName.DCTDecode);
                }
            }
        }
    }
}
