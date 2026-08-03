package com.lucsartech.squish.http.dto;

/**
 * Active compression profile settings, including watermark and PDF/A.
 * Field names are a public JSON contract - do not rename.
 */
public record ActiveProfileInfo(String name, String mode,
        float scaleFactor, float jpegQuality, boolean lossless,
        boolean watermarkEnabled, String watermarkText, String watermarkPosition, float watermarkOpacity,
        boolean pdfaEnabled, String pdfaConformance) {}
