package com.lucsartech.squish.compression;

import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * PDF fixtures shared by the compression tests.
 *
 * <p>Text-only PDFs are already close to optimal: running them through Squish
 * produces a slightly larger document, so a pure-compression profile reports
 * {@link CompressionResult.Skipped} rather than {@code Success}. Tests that need a
 * successful compression must therefore use an image-bearing fixture.
 */
public final class TestPdfs {

    private TestPdfs() {
    }

    /** A PDF that compresses: a photographic-ish image with plenty of redundancy. */
    public static byte[] withImage() throws IOException {
        return withImage(600, 400, BufferedImage.TYPE_INT_RGB);
    }

    public static byte[] withImage(int width, int height, int imageType) throws IOException {
        BufferedImage img = new BufferedImage(width, height, imageType);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.RED);
        g.fillOval(width / 8, height / 8, width / 2, height / 2);
        g.dispose();

        // Photographic noise: near-incompressible for Flate, cheap for JPEG. A smooth
        // gradient would not do — Flate already stores it in a few hundred bytes.
        var rnd = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int base = img.getRGB(x, y);
                int r = clamp(((base >> 16) & 0xFF) + rnd.nextInt(80) - 40);
                int gr = clamp(((base >> 8) & 0xFF) + rnd.nextInt(80) - 40);
                int b = clamp((base & 0xFF) + rnd.nextInt(80) - 40);
                img.setRGB(x, y, (r << 16) | (gr << 8) | b);
            }
        }

        var png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);

        try (var out = new ByteArrayOutputStream()) {
            try (var writer = new PdfWriter(out);
                 var pdfDoc = new PdfDocument(writer);
                 var document = new Document(pdfDoc)) {
                document.add(new Image(ImageDataFactory.create(png.toByteArray())));
            }
            return out.toByteArray();
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** A text-only PDF, which a pure-compression profile cannot shrink. */
    public static byte[] textOnly() throws IOException {
        try (var out = new ByteArrayOutputStream()) {
            try (var writer = new PdfWriter(out);
                 var pdfDoc = new PdfDocument(writer);
                 var document = new Document(pdfDoc)) {
                document.add(new Paragraph("Test PDF Document"));
                document.add(new Paragraph("This is a test document for compression testing."));
                document.add(new Paragraph("Lorem ipsum dolor sit amet, consectetur adipiscing elit."));
            }
            return out.toByteArray();
        }
    }
}
