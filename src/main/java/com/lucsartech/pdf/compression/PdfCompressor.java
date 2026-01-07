package com.lucsartech.pdf.compression;

import com.itextpdf.kernel.pdf.*;
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject;
import com.lucsartech.pdf.config.CompressionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Modern PDF compression engine using iText 8.
 * Thread-safe and optimized for virtual thread execution.
 */
public final class PdfCompressor {

    private static final Logger log = LoggerFactory.getLogger(PdfCompressor.class);

    /** PDF magic bytes: %PDF- */
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    private final CompressionMode mode;

    public PdfCompressor(CompressionMode mode) {
        this.mode = Objects.requireNonNull(mode, "Compression mode is required");
    }

    /**
     * Compress a PDF document.
     *
     * @param id       Document identifier for tracking
     * @param filename Original filename
     * @param input    PDF bytes to compress
     * @return CompressionResult indicating success, failure, or skipped
     */
    public CompressionResult compress(long id, String filename, byte[] input) {
        Objects.requireNonNull(input, "Input bytes cannot be null");

        long originalSize = input.length;

        // Check if this is actually a PDF
        if (!isPdf(input)) {
            log.debug("Skipped id={} ({}): not a PDF file ({} bytes)", id, filename, originalSize);
            return CompressionResult.Skipped.notPdf(id, filename, originalSize);
        }

        var startTime = Instant.now();

        try {
            byte[] compressed = compressInternal(input);
            var duration = Duration.between(startTime, Instant.now());

            log.debug("Compressed PDF id={} ({}) in {}ms: {} -> {} bytes ({}% saved)",
                    id, filename, duration.toMillis(), originalSize, compressed.length,
                    String.format("%.1f", (1.0 - (double) compressed.length / originalSize) * 100));

            return new CompressionResult.Success(
                    id,
                    filename,
                    compressed,
                    originalSize,
                    compressed.length,
                    duration
            );
        } catch (Exception e) {
            log.warn("Failed to compress PDF id={} ({}): {}", id, filename, e.getMessage());
            return CompressionResult.Failure.of(id, filename, e);
        }
    }

    /**
     * Check if the input bytes represent a PDF file.
     */
    private boolean isPdf(byte[] input) {
        if (input == null || input.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (input[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private byte[] compressInternal(byte[] input) throws IOException {
        try (var inputStream = new ByteArrayInputStream(input);
             var reader = new PdfReader(inputStream);
             var outputStream = new ByteArrayOutputStream()) {

            var writerProperties = new WriterProperties()
                    .setFullCompressionMode(true);

            try (var writer = new PdfWriter(outputStream, writerProperties);
                 var pdf = new PdfDocument(reader, writer)) {

                if (!mode.isLossless()) {
                    processImages(pdf);
                }
            }

            return outputStream.toByteArray();
        }
    }

    private void processImages(PdfDocument pdf) {
        int objectCount = pdf.getNumberOfPdfObjects();

        for (int i = 1; i <= objectCount; i++) {
            PdfObject obj = pdf.getPdfObject(i);

            if (obj instanceof PdfStream stream && isImageStream(stream)) {
                try {
                    compressImageStream(stream);
                } catch (Exception e) {
                    log.trace("Could not compress image at object {}: {}", i, e.getMessage());
                }
            }
        }
    }

    private boolean isImageStream(PdfStream stream) {
        PdfObject subtype = stream.get(PdfName.Subtype);
        return PdfName.Image.equals(subtype);
    }

    private void compressImageStream(PdfStream stream) throws IOException {
        var imageXObject = new PdfImageXObject(stream);
        BufferedImage originalImage = imageXObject.getBufferedImage();

        if (originalImage == null) {
            return;
        }

        BufferedImage processedImage = scaleImage(originalImage);
        byte[] compressedBytes = encodeAsJpeg(processedImage);

        stream.setData(compressedBytes);
        stream.put(PdfName.Filter, PdfName.DCTDecode);
    }

    private BufferedImage scaleImage(BufferedImage original) {
        int newWidth = Math.max(1, Math.round(original.getWidth() * mode.scaleFactor()));
        int newHeight = Math.max(1, Math.round(original.getHeight() * mode.scaleFactor()));

        if (newWidth == original.getWidth() && newHeight == original.getHeight()) {
            // Convert to RGB if needed, but don't scale
            if (original.getType() == BufferedImage.TYPE_INT_RGB) {
                return original;
            }
            BufferedImage rgb = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(original, 0, 0, null);
            g.dispose();
            return rgb;
        }

        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(original, 0, 0, newWidth, newHeight, null);
        g.dispose();

        return scaled;
    }

    private byte[] encodeAsJpeg(BufferedImage image) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");

        if (!writers.hasNext()) {
            throw new IOException("No JPEG encoder available");
        }

        ImageWriter jpegWriter = writers.next();

        try (var outputStream = new ByteArrayOutputStream();
             var imageOutputStream = new MemoryCacheImageOutputStream(outputStream)) {

            ImageWriteParam params = jpegWriter.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(mode.jpegQuality());

            jpegWriter.setOutput(imageOutputStream);
            jpegWriter.write(null, new IIOImage(image, null, null), params);

            return outputStream.toByteArray();
        } finally {
            jpegWriter.dispose();
        }
    }

    public CompressionMode mode() {
        return mode;
    }
}
