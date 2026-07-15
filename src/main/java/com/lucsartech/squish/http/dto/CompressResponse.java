package com.lucsartech.squish.http.dto;

public record CompressResponse(boolean success,
                               String filename,
                               long originalSize,
                               long compressedSize,
                               double savingsPercent,
                               long durationMs,
                               String profile,
                               String pdfBase64) {}
