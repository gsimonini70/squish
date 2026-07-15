package com.lucsartech.squish.compression;

import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.*;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState;
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject;
import com.itextpdf.kernel.utils.PdfMerger;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.pdfa.PdfADocument;
import com.itextpdf.kernel.pdf.PdfAConformanceLevel;
import com.itextpdf.kernel.pdf.PdfOutputIntent;
import com.itextpdf.kernel.pdf.EncryptionConstants;
import com.lucsartech.squish.config.CompressionMode;
import com.lucsartech.squish.config.CompressionProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Modern PDF compression engine using iText 8.
 * Thread-safe and optimized for virtual thread execution.
 * Supports both legacy CompressionMode and new CompressionProfile.
 */
public final class Squish {

    private static final Logger log = LoggerFactory.getLogger(Squish.class);

    /** PDF magic bytes: %PDF- */
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    private final CompressionProfile profile;
    private final String inputPassword;
    private final String outputPassword;

    /**
     * Create compressor with a compression profile.
     */
    public Squish(CompressionProfile profile) {
        this(profile, null, null);
    }

    /**
     * Create compressor with encryption support.
     *
     * @param profile        Compression profile
     * @param inputPassword  Password to decrypt input PDF (null if not encrypted)
     * @param outputPassword Password to encrypt output PDF (null for no encryption)
     */
    public Squish(CompressionProfile profile, String inputPassword, String outputPassword) {
        this.profile = Objects.requireNonNull(profile, "Compression profile is required");
        this.inputPassword = inputPassword;
        this.outputPassword = outputPassword;
    }

    /**
     * Create compressor with legacy compression mode.
     * @deprecated Use CompressionProfile constructor instead
     */
    @Deprecated
    public Squish(CompressionMode mode) {
        this.profile = new CompressionProfile("legacy", Objects.requireNonNull(mode, "Compression mode is required"));
        this.inputPassword = null;
        this.outputPassword = null;
    }

    /**
     * Compress a PDF document.
     *
     * @param id       Document identifier for tracking (OTTI_ID)
     * @param ctr      Counter for composite PK (OTTI_CTR)
     * @param filename Original filename
     * @param input    PDF bytes to compress
     * @return CompressionResult indicating success, failure, or skipped
     */
    public CompressionResult compress(long id, long ctr, String filename, byte[] input) {
        Objects.requireNonNull(input, "Input bytes cannot be null");

        long originalSize = input.length;

        // Check if this is actually a PDF
        if (!isPdf(input)) {
            log.debug("Skipped id={}/ctr={} ({}): not a PDF file ({} bytes)", id, ctr, filename, originalSize);
            return CompressionResult.Skipped.notPdf(id, ctr, filename, originalSize);
        }

        var startTime = Instant.now();

        try {
            byte[] compressed = compressInternal(input);
            var duration = Duration.between(startTime, Instant.now());

            if (compressed.length >= originalSize && isPureCompression()) {
                log.debug("Skipped id={}/ctr={} ({}): no size gain ({} -> {} bytes)",
                        id, ctr, filename, originalSize, compressed.length);
                return CompressionResult.Skipped.noGain(id, ctr, filename, originalSize, compressed.length);
            }

            log.debug("Compressed PDF id={}/ctr={} ({}) in {}ms: {} -> {} bytes ({}% saved)",
                    id, ctr, filename, duration.toMillis(), originalSize, compressed.length,
                    String.format("%.1f", (1.0 - (double) compressed.length / originalSize) * 100));

            return new CompressionResult.Success(
                    id,
                    ctr,
                    filename,
                    compressed,
                    originalSize,
                    compressed.length,
                    duration
            );
        } catch (Exception e) {
            log.warn("Failed to compress PDF id={}/ctr={} ({}): {}", id, ctr, filename, e.getMessage());
            return CompressionResult.Failure.of(id, ctr, filename, e);
        }
    }

    /**
     * True when the only purpose of this run is to make the file smaller. PDF/A
     * conversion, watermarking and re-encryption all legitimately grow the document,
     * so for those the output must be kept regardless of size.
     */
    private boolean isPureCompression() {
        return !profile.isPdfaEnabled()
                && !profile.isWatermarkEnabled()
                && (outputPassword == null || outputPassword.isEmpty());
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
        // If PDF/A is enabled, use the PDF/A conversion path
        if (profile.isPdfaEnabled()) {
            return compressToPdfA(input);
        }

        // Standard compression path
        try (var inputStream = new ByteArrayInputStream(input);
             var outputStream = new ByteArrayOutputStream()) {

            // Create reader with password if provided
            PdfReader reader = createReader(inputStream);

            // Configure writer properties
            var writerProperties = new WriterProperties()
                    .setFullCompressionMode(true);

            // Add encryption if output password is specified
            if (outputPassword != null && !outputPassword.isEmpty()) {
                writerProperties.setStandardEncryption(
                        outputPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        outputPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        EncryptionConstants.ALLOW_PRINTING | EncryptionConstants.ALLOW_COPY,
                        EncryptionConstants.ENCRYPTION_AES_256
                );
            }

            try (var writer = new PdfWriter(outputStream, writerProperties);
                 var pdf = new PdfDocument(reader, writer)) {

                if (!profile.isLossless()) {
                    processImages(pdf);
                }

                // Apply watermark if enabled
                if (profile.isWatermarkEnabled() && profile.getWatermarkText() != null) {
                    applyWatermark(pdf);
                }
            }

            return outputStream.toByteArray();
        }
    }

    /**
     * Create PdfReader with optional password for encrypted PDFs.
     */
    private PdfReader createReader(InputStream inputStream) throws IOException {
        if (inputPassword != null && !inputPassword.isEmpty()) {
            var readerProperties = new ReaderProperties()
                    .setPassword(inputPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new PdfReader(inputStream, readerProperties);
        }
        return new PdfReader(inputStream);
    }

    /**
     * Compress PDF and convert to PDF/A format.
     * Uses a two-pass approach: first compress, then convert to PDF/A.
     */
    private byte[] compressToPdfA(byte[] input) throws IOException {
        // First pass: compress the PDF normally
        byte[] compressed = compressWithoutPdfA(input);

        // Second pass: convert to PDF/A
        return convertToPdfA(compressed);
    }

    /**
     * Compress PDF without PDF/A conversion (for first pass).
     */
    private byte[] compressWithoutPdfA(byte[] input) throws IOException {
        try (var inputStream = new ByteArrayInputStream(input);
             var outputStream = new ByteArrayOutputStream()) {

            // Create reader with password if provided
            PdfReader reader = createReader(inputStream);

            var writerProperties = new WriterProperties()
                    .setFullCompressionMode(true);

            // Note: Don't encrypt here if PDF/A is the target (encryption applied after conversion)

            try (var writer = new PdfWriter(outputStream, writerProperties);
                 var pdf = new PdfDocument(reader, writer)) {

                if (!profile.isLossless()) {
                    processImages(pdf);
                }

                // Apply watermark if enabled
                if (profile.isWatermarkEnabled() && profile.getWatermarkText() != null) {
                    applyWatermark(pdf);
                }
            }

            return outputStream.toByteArray();
        }
    }

    /**
     * Convert a PDF to PDF/A format.
     */
    private byte[] convertToPdfA(byte[] input) throws IOException {
        try (var inputStream = new ByteArrayInputStream(input);
             var reader = new PdfReader(inputStream);
             var sourcePdf = new PdfDocument(reader);
             var outputStream = new ByteArrayOutputStream()) {

            // Get conformance level
            PdfAConformanceLevel conformance = getPdfAConformance();

            // Create output intent with sRGB ICC profile
            PdfOutputIntent outputIntent = createOutputIntent();

            var writerProperties = new WriterProperties()
                    .setFullCompressionMode(true);

            try (var writer = new PdfWriter(outputStream, writerProperties);
                 var pdfA = new PdfADocument(writer, conformance, outputIntent)) {

                // Set document metadata for PDF/A compliance
                setPdfAMetadata(pdfA);

                // Copy all pages from source to PDF/A document
                var merger = new PdfMerger(pdfA);
                merger.merge(sourcePdf, 1, sourcePdf.getNumberOfPages());

                log.trace("Converted PDF to {} ({} pages)",
                        profile.getPdfaConformance().displayName(),
                        sourcePdf.getNumberOfPages());
            }

            return outputStream.toByteArray();
        }
    }

    /**
     * Get iText PdfAConformanceLevel from profile setting.
     */
    private PdfAConformanceLevel getPdfAConformance() {
        return switch (profile.getPdfaConformance()) {
            case PDF_A_1B -> PdfAConformanceLevel.PDF_A_1B;
            case PDF_A_2B -> PdfAConformanceLevel.PDF_A_2B;
            case PDF_A_3B -> PdfAConformanceLevel.PDF_A_3B;
        };
    }

    /**
     * Create PDF output intent with sRGB ICC color profile.
     */
    private PdfOutputIntent createOutputIntent() throws IOException {
        // Get sRGB ICC profile from Java runtime
        ICC_Profile iccProfile = ICC_Profile.getInstance(ColorSpace.CS_sRGB);
        byte[] iccData = iccProfile.getData();

        try (InputStream iccStream = new ByteArrayInputStream(iccData)) {
            return new PdfOutputIntent(
                    "Custom",
                    "",
                    "http://www.color.org",
                    "sRGB IEC61966-2.1",
                    iccStream
            );
        }
    }

    /**
     * Set required PDF/A metadata.
     */
    private void setPdfAMetadata(PdfADocument pdfA) {
        PdfDocumentInfo info = pdfA.getDocumentInfo();
        info.setTitle("PDF/A Document");
        info.setCreator("Squish PDF Compressor");
        info.setProducer("Squish v3.0 with iText");

        // PDF/A requires the document to be tagged for accessibility (PDF/A-1a, 2a, 3a)
        // For "b" conformance levels (basic), tagging is not required
        // but we add basic catalog metadata
        PdfCatalog catalog = pdfA.getCatalog();
        catalog.setLang(new PdfString("en"));
        catalog.setViewerPreferences(new PdfViewerPreferences().setDisplayDocTitle(true));
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
        // A stencil mask is 1 bit per pixel and carries no colour space; a DCT-encoded
        // replacement cannot represent it.
        PdfBoolean imageMask = stream.getAsBoolean(PdfName.ImageMask);
        if (imageMask != null && imageMask.getValue()) {
            return;
        }

        var imageXObject = new PdfImageXObject(stream);
        BufferedImage originalImage = imageXObject.getBufferedImage();

        if (originalImage == null) {
            return;
        }

        // A soft mask must stay single-component, so grayscale sources stay grayscale.
        boolean grayscale = PdfName.DeviceGray.equals(stream.get(PdfName.ColorSpace));
        int targetType = grayscale ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB;

        BufferedImage processedImage = scaleImage(originalImage, targetType);
        byte[] compressedBytes = encodeAsJpeg(processedImage);

        // Re-encoding 1-bit scans or already-optimal images inflates them.
        if (compressedBytes.length >= stream.getBytes(false).length) {
            return;
        }

        stream.setData(compressedBytes);
        stream.put(PdfName.Filter, PdfName.DCTDecode);
        // The dictionary describes the bytes, so it has to follow them.
        stream.put(PdfName.Width, new PdfNumber(processedImage.getWidth()));
        stream.put(PdfName.Height, new PdfNumber(processedImage.getHeight()));
        stream.put(PdfName.ColorSpace, grayscale ? PdfName.DeviceGray : PdfName.DeviceRGB);
        stream.put(PdfName.BitsPerComponent, new PdfNumber(8));
        stream.remove(PdfName.DecodeParms);
        stream.remove(PdfName.Decode);
    }

    private BufferedImage scaleImage(BufferedImage original, int targetType) {
        float scaleFactor = profile.getEffectiveScaleFactor();
        int newWidth = Math.max(1, Math.round(original.getWidth() * scaleFactor));
        int newHeight = Math.max(1, Math.round(original.getHeight() * scaleFactor));

        if (newWidth == original.getWidth() && newHeight == original.getHeight()
                && original.getType() == targetType) {
            return original;
        }

        BufferedImage scaled = new BufferedImage(newWidth, newHeight, targetType);
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
            params.setCompressionQuality(profile.getEffectiveJpegQuality());

            jpegWriter.setOutput(imageOutputStream);
            jpegWriter.write(null, new IIOImage(image, null, null), params);

            return outputStream.toByteArray();
        } finally {
            jpegWriter.dispose();
        }
    }

    // ==================== Watermark Support ====================

    /**
     * Apply watermark to all pages of the PDF document.
     */
    private void applyWatermark(PdfDocument pdf) {
        String text = profile.getWatermarkText();
        if (text == null || text.isBlank()) {
            return;
        }

        try {
            PdfFont font = getWatermarkFont();
            DeviceRgb color = parseColor(profile.getWatermarkColor());
            float opacity = profile.getWatermarkOpacity();
            int fontSize = profile.getWatermarkFontSize();

            // Create transparency state
            PdfExtGState gState = new PdfExtGState();
            gState.setFillOpacity(opacity);

            int pageCount = pdf.getNumberOfPages();
            for (int i = 1; i <= pageCount; i++) {
                PdfPage page = pdf.getPage(i);
                Rectangle pageSize = page.getPageSize();

                PdfCanvas canvas = new PdfCanvas(page);
                canvas.saveState();
                canvas.setExtGState(gState);
                canvas.setFillColor(color);

                drawWatermark(canvas, font, fontSize, text, pageSize, profile.getWatermarkPosition());

                canvas.restoreState();
            }

            log.trace("Applied watermark '{}' to {} pages", text, pageCount);

        } catch (Exception e) {
            log.warn("Failed to apply watermark: {}", e.getMessage());
        }
    }

    /**
     * Get the font for watermark text.
     */
    private PdfFont getWatermarkFont() throws IOException {
        String fontName = profile.getWatermarkFontName();

        // Map common font names to iText standard fonts
        return switch (fontName.toLowerCase()) {
            case "helvetica", "arial", "sans-serif" -> PdfFontFactory.createFont(StandardFonts.HELVETICA);
            case "helvetica-bold", "arial-bold" -> PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
            case "times", "times new roman", "serif" -> PdfFontFactory.createFont(StandardFonts.TIMES_ROMAN);
            case "courier", "monospace" -> PdfFontFactory.createFont(StandardFonts.COURIER);
            default -> PdfFontFactory.createFont(StandardFonts.HELVETICA);
        };
    }

    /**
     * Parse hex color string to DeviceRgb.
     */
    private DeviceRgb parseColor(String hexColor) {
        if (hexColor == null || hexColor.isBlank()) {
            return new DeviceRgb(128, 128, 128); // Default gray
        }

        try {
            String hex = hexColor.startsWith("#") ? hexColor.substring(1) : hexColor;
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            return new DeviceRgb(r, g, b);
        } catch (Exception e) {
            return new DeviceRgb(128, 128, 128); // Fallback to gray
        }
    }

    /**
     * Draw watermark text on the canvas based on position.
     */
    private void drawWatermark(PdfCanvas canvas, PdfFont font, int fontSize,
                               String text, Rectangle pageSize,
                               CompressionProfile.WatermarkPosition position) {

        float width = pageSize.getWidth();
        float height = pageSize.getHeight();
        float textWidth = font.getWidth(text, fontSize);

        switch (position) {
            case CENTER -> {
                float x = (width - textWidth) / 2;
                float y = height / 2;
                canvas.beginText()
                        .setFontAndSize(font, fontSize)
                        .moveText(x, y)
                        .showText(text)
                        .endText();
            }

            case TOP_LEFT -> {
                float margin = 20;
                canvas.beginText()
                        .setFontAndSize(font, fontSize)
                        .moveText(margin, height - margin - fontSize)
                        .showText(text)
                        .endText();
            }

            case TOP_RIGHT -> {
                float margin = 20;
                canvas.beginText()
                        .setFontAndSize(font, fontSize)
                        .moveText(width - textWidth - margin, height - margin - fontSize)
                        .showText(text)
                        .endText();
            }

            case BOTTOM_LEFT -> {
                float margin = 20;
                canvas.beginText()
                        .setFontAndSize(font, fontSize)
                        .moveText(margin, margin)
                        .showText(text)
                        .endText();
            }

            case BOTTOM_RIGHT -> {
                float margin = 20;
                canvas.beginText()
                        .setFontAndSize(font, fontSize)
                        .moveText(width - textWidth - margin, margin)
                        .showText(text)
                        .endText();
            }

            case DIAGONAL -> {
                // Draw rotated text diagonally across the page
                float centerX = width / 2;
                float centerY = height / 2;
                double angle = Math.atan2(height, width); // Diagonal angle

                canvas.concatMatrix(
                        (float) Math.cos(angle), (float) Math.sin(angle),
                        (float) -Math.sin(angle), (float) Math.cos(angle),
                        centerX, centerY
                );

                canvas.beginText()
                        .setFontAndSize(font, fontSize)
                        .moveText(-textWidth / 2, -fontSize / 2)
                        .showText(text)
                        .endText();
            }

            case TILED -> {
                // Draw watermark in a tiled pattern
                float spacingX = textWidth + 100;
                float spacingY = fontSize * 3;
                double angle = Math.toRadians(-30); // 30 degrees counter-clockwise

                for (float y = -height; y < height * 2; y += spacingY) {
                    for (float x = -width; x < width * 2; x += spacingX) {
                        canvas.saveState();
                        canvas.concatMatrix(
                                (float) Math.cos(angle), (float) Math.sin(angle),
                                (float) -Math.sin(angle), (float) Math.cos(angle),
                                x, y
                        );
                        canvas.beginText()
                                .setFontAndSize(font, fontSize)
                                .moveText(0, 0)
                                .showText(text)
                                .endText();
                        canvas.restoreState();
                    }
                }
            }
        }
    }

    /**
     * Get the compression profile used by this compressor.
     */
    public CompressionProfile profile() {
        return profile;
    }

    /**
     * Get the compression mode (legacy compatibility).
     * @deprecated Use profile() instead
     */
    @Deprecated
    public CompressionMode mode() {
        return profile.getMode();
    }
}
