package com.lucsartech.squish.http;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.ReaderProperties;
import com.lucsartech.squish.config.SquishProperties;
import com.lucsartech.squish.http.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * REST API: PDF thumbnail generation via multipart upload.
 *
 * The rendering is a PLACEHOLDER (white rectangle + border + "Page N/T"),
 * not a real page render.
 */
@RestController
@RequestMapping("/api/thumbnail")
public final class ThumbnailController {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailController.class);

    private final SquishProperties properties;

    public ThumbnailController(SquishProperties properties) {
        this.properties = properties;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> thumbnail(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "width", defaultValue = "200") int width,
            @RequestParam(value = "format", defaultValue = "png") String format,
            @RequestParam(value = "password", required = false) String password) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse(400, "No file provided."));
        }

        try {
            byte[] thumbnail = generateThumbnail(file.getBytes(), page, width, format, password);

            // The test asserts the Content-Type is exactly image/jpeg or image/png.
            String contentType = "jpeg".equalsIgnoreCase(format) ? "image/jpeg" : "image/png";

            log.info("REST API: Generated thumbnail for page {} ({}x{} {})", page, width, "auto", format);

            return ResponseEntity.ok()
                    .header("Content-Type", contentType)
                    .body(thumbnail);

        } catch (Exception e) {
            log.error("REST API thumbnail error", e);
            // Out-of-range page throws IllegalArgumentException("Invalid page number: ...");
            // preserve the "Error generating thumbnail: " prefix so the 500 message
            // still contains "invalid page".
            return ResponseEntity.status(500)
                    .body(new ErrorResponse(500, "Error generating thumbnail: " + e.getMessage()));
        }
    }

    /**
     * Generate thumbnail from a PDF page. PLACEHOLDER render only.
     */
    private byte[] generateThumbnail(byte[] pdfData, int pageNum, int width, String format, String password)
            throws Exception {
        // The reader is a resource too: if the PdfDocument constructor throws on a
        // malformed PDF, it must still be closed.
        try (var inputStream = new ByteArrayInputStream(pdfData);
             PdfReader reader = (password != null && !password.isEmpty())
                     ? new PdfReader(inputStream,
                             new ReaderProperties().setPassword(password.getBytes(StandardCharsets.UTF_8)))
                     : new PdfReader(inputStream);
             PdfDocument pdfDoc = new PdfDocument(reader)) {

            int totalPages = pdfDoc.getNumberOfPages();
            if (pageNum < 1 || pageNum > totalPages) {
                throw new IllegalArgumentException("Invalid page number: " + pageNum + " (total: " + totalPages + ")");
            }

            var page = pdfDoc.getPage(pageNum);
            var pageSize = page.getPageSize();

            // Calculate dimensions maintaining aspect ratio
            float aspectRatio = pageSize.getHeight() / pageSize.getWidth();
            int height = (int) (width * aspectRatio);

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var g2d = image.createGraphics();
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, width, height);

            // Placeholder: page info centered, no real render.
            g2d.setColor(Color.DARK_GRAY);
            g2d.setFont(new Font("SansSerif", Font.BOLD, 14));
            String text = "Page " + pageNum + "/" + totalPages;
            var fm = g2d.getFontMetrics();
            int textX = (width - fm.stringWidth(text)) / 2;
            int textY = height / 2;
            g2d.drawString(text, textX, textY);

            // Draw border
            g2d.setColor(Color.LIGHT_GRAY);
            g2d.drawRect(0, 0, width - 1, height - 1);
            g2d.dispose();

            var outputStream = new ByteArrayOutputStream();
            String imageFormat = "jpeg".equalsIgnoreCase(format) ? "JPEG" : "PNG";
            ImageIO.write(image, imageFormat, outputStream);
            return outputStream.toByteArray();
        }
    }
}
