package com.lucsartech.squish.http.dto;

public record ProfileInfo(String name,
                          String description,
                          String mode,
                          float scaleFactor,
                          float jpegQuality,
                          boolean lossless,
                          boolean watermarkEnabled,
                          boolean pdfaEnabled) {}
