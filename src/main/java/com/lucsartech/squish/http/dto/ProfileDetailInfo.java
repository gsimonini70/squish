package com.lucsartech.squish.http.dto;

/**
 * Full profile description. {@code customScaleFactor} and {@code customJpegQuality}
 * are nullable on purpose - a null must be omitted from JSON (Jackson non_null).
 */
public record ProfileDetailInfo(String name,
                                String description,
                                String mode,
                                Float customScaleFactor,
                                Float customJpegQuality,
                                boolean watermarkEnabled,
                                String watermarkText,
                                String watermarkPosition,
                                float watermarkOpacity,
                                int watermarkFontSize,
                                String watermarkColor,
                                boolean pdfaEnabled,
                                String pdfaConformance) {}
