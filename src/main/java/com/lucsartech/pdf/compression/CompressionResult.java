package com.lucsartech.pdf.compression;

import java.time.Duration;
import java.util.Optional;

/**
 * Result of a PDF compression operation.
 * Sealed interface for type-safe success/failure handling.
 */
public sealed interface CompressionResult {

    long id();
    String filename();

    record Success(
            long id,
            String filename,
            byte[] compressedData,
            long originalSize,
            long compressedSize,
            Duration processingTime
    ) implements CompressionResult {

        public double compressionRatio() {
            return originalSize > 0 ? (double) compressedSize / originalSize : 1.0;
        }

        public double savingsPercent() {
            return (1.0 - compressionRatio()) * 100.0;
        }

        public long savedBytes() {
            return originalSize - compressedSize;
        }
    }

    record Failure(
            long id,
            String filename,
            String errorMessage,
            Optional<Throwable> cause
    ) implements CompressionResult {

        public static Failure of(long id, String filename, Throwable cause) {
            return new Failure(id, filename, cause.getMessage(), Optional.of(cause));
        }

        public static Failure of(long id, String filename, String message) {
            return new Failure(id, filename, message, Optional.empty());
        }
    }

    /**
     * Result for non-PDF files that are skipped.
     */
    record Skipped(
            long id,
            String filename,
            long size,
            String reason
    ) implements CompressionResult {

        public static Skipped notPdf(long id, String filename, long size) {
            return new Skipped(id, filename, size, "Not a PDF file");
        }
    }

    default boolean isSuccess() {
        return this instanceof Success;
    }

    default boolean isSkipped() {
        return this instanceof Skipped;
    }

    default boolean isFailure() {
        return this instanceof Failure;
    }

    default Optional<Success> asSuccess() {
        return this instanceof Success s ? Optional.of(s) : Optional.empty();
    }

    default Optional<Failure> asFailure() {
        return this instanceof Failure f ? Optional.of(f) : Optional.empty();
    }
}
