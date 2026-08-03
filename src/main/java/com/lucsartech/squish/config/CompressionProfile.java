package com.lucsartech.squish.config;

import java.util.Objects;

/**
 * Compression profile with customizable settings.
 * Profiles allow saving and reusing compression configurations.
 *
 * Can be based on a predefined mode or use custom scale/quality values.
 */
public class CompressionProfile {

    private String name;
    private String description;

    // Compression settings (either mode-based or custom)
    private CompressionMode mode;
    private Float customScaleFactor;
    private Float customJpegQuality;

    // Watermark settings (for future implementation)
    private boolean watermarkEnabled = false;
    private String watermarkText;
    private WatermarkPosition watermarkPosition = WatermarkPosition.CENTER;
    private float watermarkOpacity = 0.3f;
    private String watermarkFontName = "Helvetica";
    private int watermarkFontSize = 48;
    private String watermarkColor = "#888888";

    // PDF/A settings (for future implementation)
    private boolean pdfaEnabled = false;
    private PdfAConformance pdfaConformance = PdfAConformance.PDF_A_2B;

    /**
     * Watermark position options.
     */
    public enum WatermarkPosition {
        CENTER,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        DIAGONAL,
        TILED
    }

    /**
     * PDF/A conformance levels.
     */
    public enum PdfAConformance {
        PDF_A_1B("PDF/A-1b", "Basic conformance, visual appearance"),
        PDF_A_2B("PDF/A-2b", "ISO 32000-1, JPEG2000 support"),
        PDF_A_3B("PDF/A-3b", "Allows embedded files");

        private final String displayName;
        private final String description;

        PdfAConformance(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String displayName() { return displayName; }
        public String description() { return description; }
    }

    // ==================== Constructors ====================

    public CompressionProfile() {
        // Default constructor for YAML binding
    }

    public CompressionProfile(String name, CompressionMode mode) {
        this.name = Objects.requireNonNull(name);
        this.mode = Objects.requireNonNull(mode);
        this.description = mode.description();
    }

    public CompressionProfile(String name, float scaleFactor, float jpegQuality) {
        this.name = Objects.requireNonNull(name);
        this.customScaleFactor = scaleFactor;
        this.customJpegQuality = jpegQuality;
        this.description = String.format("Custom: %.0f%% scale, %.0f%% quality",
                scaleFactor * 100, jpegQuality * 100);
    }

    // ==================== Effective Settings ====================

    /**
     * Get effective scale factor (from mode or custom).
     */
    public float getEffectiveScaleFactor() {
        if (customScaleFactor != null) {
            return customScaleFactor;
        }
        return mode != null ? mode.scaleFactor() : 1.0f;
    }

    /**
     * Get effective JPEG quality (from mode or custom).
     */
    public float getEffectiveJpegQuality() {
        if (customJpegQuality != null) {
            return customJpegQuality;
        }
        return mode != null ? mode.jpegQuality() : 1.0f;
    }

    /**
     * Check if this is a lossless profile.
     */
    public boolean isLossless() {
        if (customScaleFactor != null || customJpegQuality != null) {
            return getEffectiveScaleFactor() >= 1.0f && getEffectiveJpegQuality() >= 1.0f;
        }
        return mode != null && mode.isLossless();
    }

    /**
     * Check if custom values are used instead of mode.
     */
    public boolean isCustom() {
        return customScaleFactor != null || customJpegQuality != null;
    }

    // ==================== Predefined Profiles ====================

    /**
     * Create standard archival profile (lossless + PDF/A).
     */
    public static CompressionProfile archival() {
        CompressionProfile profile = new CompressionProfile("archival", CompressionMode.LOSSLESS);
        profile.setDescription("Archival quality - lossless compression with PDF/A compliance");
        profile.setPdfaEnabled(true);
        profile.setPdfaConformance(PdfAConformance.PDF_A_2B);
        return profile;
    }

    /**
     * Create standard office profile (medium compression).
     */
    public static CompressionProfile office() {
        CompressionProfile profile = new CompressionProfile("office", CompressionMode.MEDIUM);
        profile.setDescription("Office documents - balanced compression");
        return profile;
    }

    /**
     * Create maximum compression profile.
     */
    public static CompressionProfile maximum() {
        CompressionProfile profile = new CompressionProfile("maximum", CompressionMode.AGGRESSIVE);
        profile.setDescription("Maximum compression - smallest file size");
        return profile;
    }

    /**
     * Create confidential profile with watermark.
     */
    public static CompressionProfile confidential() {
        CompressionProfile profile = new CompressionProfile("confidential", CompressionMode.MEDIUM);
        profile.setDescription("Confidential documents with watermark");
        profile.setWatermarkEnabled(true);
        profile.setWatermarkText("CONFIDENTIAL");
        profile.setWatermarkPosition(WatermarkPosition.DIAGONAL);
        return profile;
    }

    // ==================== Getters and Setters ====================

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public CompressionMode getMode() { return mode; }
    public void setMode(CompressionMode mode) { this.mode = mode; }

    public Float getCustomScaleFactor() { return customScaleFactor; }
    public void setCustomScaleFactor(Float customScaleFactor) { this.customScaleFactor = customScaleFactor; }

    public Float getCustomJpegQuality() { return customJpegQuality; }
    public void setCustomJpegQuality(Float customJpegQuality) { this.customJpegQuality = customJpegQuality; }

    public boolean isWatermarkEnabled() { return watermarkEnabled; }
    public void setWatermarkEnabled(boolean watermarkEnabled) { this.watermarkEnabled = watermarkEnabled; }

    public String getWatermarkText() { return watermarkText; }
    public void setWatermarkText(String watermarkText) { this.watermarkText = watermarkText; }

    public WatermarkPosition getWatermarkPosition() { return watermarkPosition; }
    public void setWatermarkPosition(WatermarkPosition watermarkPosition) { this.watermarkPosition = watermarkPosition; }

    public float getWatermarkOpacity() { return watermarkOpacity; }
    public void setWatermarkOpacity(float watermarkOpacity) { this.watermarkOpacity = watermarkOpacity; }

    public String getWatermarkFontName() { return watermarkFontName; }
    public void setWatermarkFontName(String watermarkFontName) { this.watermarkFontName = watermarkFontName; }

    public int getWatermarkFontSize() { return watermarkFontSize; }
    public void setWatermarkFontSize(int watermarkFontSize) { this.watermarkFontSize = watermarkFontSize; }

    public String getWatermarkColor() { return watermarkColor; }
    public void setWatermarkColor(String watermarkColor) { this.watermarkColor = watermarkColor; }

    public boolean isPdfaEnabled() { return pdfaEnabled; }
    public void setPdfaEnabled(boolean pdfaEnabled) { this.pdfaEnabled = pdfaEnabled; }

    public PdfAConformance getPdfaConformance() { return pdfaConformance; }
    public void setPdfaConformance(PdfAConformance pdfaConformance) { this.pdfaConformance = pdfaConformance; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("CompressionProfile{name='").append(name).append("'");
        if (mode != null) {
            sb.append(", mode=").append(mode);
        }
        if (isCustom()) {
            sb.append(", scale=").append(getEffectiveScaleFactor());
            sb.append(", quality=").append(getEffectiveJpegQuality());
        }
        if (watermarkEnabled) {
            sb.append(", watermark='").append(watermarkText).append("'");
        }
        if (pdfaEnabled) {
            sb.append(", pdfa=").append(pdfaConformance);
        }
        sb.append("}");
        return sb.toString();
    }
}
