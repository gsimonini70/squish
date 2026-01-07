package com.lucsartech.pdf.pipeline;

/**
 * Represents a PDF document to be compressed.
 * Uses sealed interface for type-safe poison pill pattern.
 */
public sealed interface PdfTask {

    /**
     * Regular PDF task with data to compress.
     */
    record Data(long id, String filename, byte[] pdf) implements PdfTask {
        public Data {
            if (pdf == null || pdf.length == 0) {
                throw new IllegalArgumentException("PDF data cannot be null or empty");
            }
        }

        public int size() {
            return pdf.length;
        }
    }

    /**
     * Poison pill to signal end of stream.
     */
    record Poison() implements PdfTask {
        public static final Poison INSTANCE = new Poison();
    }

    default boolean isPoison() {
        return this instanceof Poison;
    }

    default boolean isData() {
        return this instanceof Data;
    }
}
