package com.lucsartech.pdf.config;

/**
 * Compression quality modes with predefined settings.
 * Each mode defines scale factor and JPEG quality.
 */
public enum CompressionMode {
    LOSSLESS(1.0f, 1.0f, "No quality loss, only PDF structure optimization"),
    MEDIUM(0.75f, 0.7f, "Balanced compression with minimal quality loss"),
    AGGRESSIVE(0.5f, 0.3f, "Maximum compression, noticeable quality reduction");

    private final float scaleFactor;
    private final float jpegQuality;
    private final String description;

    CompressionMode(float scaleFactor, float jpegQuality, String description) {
        this.scaleFactor = scaleFactor;
        this.jpegQuality = jpegQuality;
        this.description = description;
    }

    public float scaleFactor() {
        return scaleFactor;
    }

    public float jpegQuality() {
        return jpegQuality;
    }

    public String description() {
        return description;
    }

    public boolean isLossless() {
        return this == LOSSLESS;
    }
}
