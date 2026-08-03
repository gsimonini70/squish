package com.lucsartech.squish.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.itextpdf.kernel.pdf.EncryptionConstants;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.WriterProperties;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.lucsartech.squish.compression.TestPdfs;
import com.lucsartech.squish.config.CompressionMode;
import com.lucsartech.squish.config.CompressionProfile;
import com.lucsartech.squish.config.SecurityConfig;
import com.lucsartech.squish.config.SquishProperties;
import com.lucsartech.squish.pipeline.ProgressTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Spring MVC slice tests for the on-demand REST endpoints
 * {@code POST /api/compress} and {@code POST /api/thumbnail}.
 *
 * <p>Both endpoints are write endpoints and therefore require HTTP Basic auth
 * ({@link SecurityConfig}). The credentials are pinned so the tests can
 * authenticate as {@code admin}; a dedicated regression test also asserts that an
 * unauthenticated POST is rejected.
 */
// Isolated config location so the real application.yml squish.* block does not
// re-bind the programmatic SquishProperties below.
@WebMvcTest(controllers = {CompressController.class, ThumbnailController.class},
        properties = "spring.config.location=classpath:/http-slice-test.yml")
@Import({SecurityConfig.class, RestApiTest.Beans.class})
@DisplayName("REST API")
class RestApiTest {

    private static final Gson GSON = new Gson();
    private static final String ADMIN_PASSWORD = "test-secret";

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class Beans {
        @Bean
        ProgressTracker progressTracker() {
            return new ProgressTracker();
        }

        @Bean
        SquishProperties squishProperties() {
            var props = new SquishProperties();
            props.setMode(CompressionMode.MEDIUM);
            props.setDryRun(false);
            props.setActiveProfile("office");

            props.getSecurity().setEnabled(true);
            props.getSecurity().setUsername("admin");
            props.getSecurity().setPassword(ADMIN_PASSWORD);

            var office = new CompressionProfile("office", CompressionMode.MEDIUM);
            office.setDescription("Office documents");

            var aggressive = new CompressionProfile("aggressive", CompressionMode.AGGRESSIVE);
            aggressive.setDescription("Maximum compression");

            props.setProfiles(Map.of(
                    "office", office,
                    "aggressive", aggressive
            ));

            props.getWatchdog().setEnabled(false);
            return props;
        }
    }

    private static MockMultipartFile pdfPart(byte[] content, String filename) {
        return new MockMultipartFile("file", filename, "application/pdf", content);
    }

    /** An encrypted PDF (text content) protected by {@code password}. */
    private static byte[] createEncryptedPdf(String password) throws IOException {
        try (var outputStream = new ByteArrayOutputStream()) {
            var writerProperties = new WriterProperties()
                    .setStandardEncryption(
                            password.getBytes(StandardCharsets.UTF_8),
                            password.getBytes(StandardCharsets.UTF_8),
                            EncryptionConstants.ALLOW_PRINTING,
                            EncryptionConstants.ENCRYPTION_AES_256
                    );

            try (var writer = new PdfWriter(outputStream, writerProperties);
                 var pdfDoc = new PdfDocument(writer);
                 var document = new Document(pdfDoc)) {
                document.add(new Paragraph("Encrypted PDF Document"));
                document.add(new Paragraph("This PDF is password protected."));
            }
            return outputStream.toByteArray();
        }
    }

    @Nested
    @DisplayName("POST /api/compress")
    class CompressEndpoint {

        @Test
        @DisplayName("should compress PDF and return binary")
        void compressPdfReturnBinary() throws Exception {
            // An image-bearing PDF is genuinely compressible; a text-only PDF would be
            // returned unchanged (Skipped.noGain) - still a valid 200, but not a real test.
            var response = mockMvc.perform(multipart("/api/compress")
                            .file(pdfPart(TestPdfs.withImage(), "test.pdf"))
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).isEqualTo("application/pdf");
            assertThat(response.getHeader("X-Original-Size")).isNotNull();
            assertThat(response.getHeader("X-Compressed-Size")).isNotNull();

            byte[] body = response.getContentAsByteArray();
            assertThat(body).isNotEmpty();
            assertThat(new String(body, 0, 5)).isEqualTo("%PDF-");
        }

        @Test
        @DisplayName("should compress PDF and return JSON")
        void compressPdfReturnJson() throws Exception {
            var response = mockMvc.perform(multipart("/api/compress")
                            .file(pdfPart(TestPdfs.withImage(), "test.pdf"))
                            .param("format", "json")
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).contains("application/json");

            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            assertThat(json.get("success").getAsBoolean()).isTrue();
            assertThat(json.get("filename").getAsString()).isEqualTo("test.pdf");
            assertThat(json.get("originalSize").getAsLong()).isGreaterThan(0);
            assertThat(json.get("compressedSize").getAsLong()).isGreaterThan(0);
            assertThat(json.get("pdfBase64").getAsString()).isNotEmpty();

            byte[] decoded = Base64.getDecoder().decode(json.get("pdfBase64").getAsString());
            assertThat(new String(decoded, 0, 5)).isEqualTo("%PDF-");
        }

        @Test
        @DisplayName("should use specified profile")
        void compressWithProfile() throws Exception {
            var response = mockMvc.perform(multipart("/api/compress")
                            .file(pdfPart(TestPdfs.withImage(), "test.pdf"))
                            .param("format", "json")
                            .param("profile", "aggressive")
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            assertThat(json.get("profile").getAsString()).isEqualTo("aggressive");
        }

        @Test
        @DisplayName("should reject non-PDF file")
        void rejectNonPdf() throws Exception {
            var response = mockMvc.perform(multipart("/api/compress")
                            .file(pdfPart("This is not a PDF".getBytes(StandardCharsets.UTF_8), "file.txt"))
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(400);
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            assertThat(json.get("message").getAsString()).containsIgnoringCase("skipped");
        }

        @Test
        @DisplayName("should reject empty request (no file)")
        void rejectEmptyRequest() throws Exception {
            var response = mockMvc.perform(multipart("/api/compress")
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(400);
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            assertThat(json.get("message").getAsString()).containsIgnoringCase("no file");
        }

        @Test
        @DisplayName("should reject GET method")
        void rejectGetMethod() throws Exception {
            var response = mockMvc.perform(get("/api/compress")
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(405);
        }

        @Test
        @DisplayName("POST without credentials should be rejected (security regression)")
        void rejectUnauthenticatedPost() throws Exception {
            var response = mockMvc.perform(multipart("/api/compress")
                            .file(pdfPart(TestPdfs.withImage(), "test.pdf")))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isIn(401, 403);
        }

        /**
         * The legacy server answered a CORS pre-flight with 204 +
         * {@code Access-Control-Allow-Methods: ...POST...}. CORS is now off by default,
         * so no wildcard origin header is emitted.
         */
        @Test
        @DisplayName("no wildcard Access-Control-Allow-Origin is emitted")
        void noWildcardCorsHeader() throws Exception {
            var response = mockMvc.perform(get("/api/compress")
                            .header("Origin", "http://evil.example.com")
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNotEqualTo("*");
        }
    }

    @Nested
    @DisplayName("Encryption Support")
    class EncryptionSupport {

        @Test
        @DisplayName("should compress encrypted PDF with password")
        void compressEncryptedPdf() throws Exception {
            String password = "secret123";
            var response = mockMvc.perform(multipart("/api/compress")
                            .file(pdfPart(createEncryptedPdf(password), "encrypted.pdf"))
                            .param("format", "json")
                            .param("password", password)
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            assertThat(json.get("success").getAsBoolean()).isTrue();
        }

        @Test
        @DisplayName("should re-encrypt output PDF")
        void reEncryptOutput() throws Exception {
            // Setting an output password disables the "no size gain" skip, so even a
            // text-only PDF is written back - here re-encrypted with the new password.
            String outputPassword = "newpassword";
            var response = mockMvc.perform(multipart("/api/compress")
                            .file(pdfPart(TestPdfs.textOnly(), "test.pdf"))
                            .param("outputPassword", outputPassword)
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);

            byte[] outputPdf = response.getContentAsByteArray();
            assertThat(outputPdf).isNotEmpty();
            assertThat(new String(outputPdf, 0, 5)).isEqualTo("%PDF-");

            String asLatin1 = new String(outputPdf, StandardCharsets.ISO_8859_1);
            assertThat(asLatin1).contains("/Encrypt");
        }
    }

    @Nested
    @DisplayName("POST /api/thumbnail")
    class ThumbnailEndpoint {

        @Test
        @DisplayName("should generate PNG thumbnail")
        void generatePngThumbnail() throws Exception {
            var response = mockMvc.perform(multipart("/api/thumbnail")
                            .file(pdfPart(TestPdfs.textOnly(), "test.pdf"))
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).isEqualTo("image/png");

            byte[] body = response.getContentAsByteArray();
            assertThat(body).isNotEmpty();
            // PNG magic bytes: 89 50 4E 47
            assertThat(body[0]).isEqualTo((byte) 0x89);
            assertThat(body[1]).isEqualTo((byte) 0x50);
            assertThat(body[2]).isEqualTo((byte) 0x4E);
            assertThat(body[3]).isEqualTo((byte) 0x47);
        }

        @Test
        @DisplayName("should generate JPEG thumbnail")
        void generateJpegThumbnail() throws Exception {
            var response = mockMvc.perform(multipart("/api/thumbnail")
                            .file(pdfPart(TestPdfs.textOnly(), "test.pdf"))
                            .param("format", "jpeg")
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).isEqualTo("image/jpeg");

            byte[] body = response.getContentAsByteArray();
            assertThat(body).isNotEmpty();
            // JPEG magic bytes: FF D8 FF
            assertThat(body[0] & 0xFF).isEqualTo(0xFF);
            assertThat(body[1] & 0xFF).isEqualTo(0xD8);
            assertThat(body[2] & 0xFF).isEqualTo(0xFF);
        }

        @Test
        @DisplayName("should respect width parameter")
        void respectWidthParameter() throws Exception {
            var response = mockMvc.perform(multipart("/api/thumbnail")
                            .file(pdfPart(TestPdfs.textOnly(), "test.pdf"))
                            .param("width", "100")
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsByteArray()).isNotEmpty();
        }

        @Test
        @DisplayName("should handle encrypted PDF with password")
        void handleEncryptedPdf() throws Exception {
            String password = "secret";
            var response = mockMvc.perform(multipart("/api/thumbnail")
                            .file(pdfPart(createEncryptedPdf(password), "encrypted.pdf"))
                            .param("password", password)
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).isEqualTo("image/png");
        }

        @Test
        @DisplayName("should reject invalid page number")
        void rejectInvalidPage() throws Exception {
            // PDF has only 1 page, request page 10
            var response = mockMvc.perform(multipart("/api/thumbnail")
                            .file(pdfPart(TestPdfs.textOnly(), "test.pdf"))
                            .param("page", "10")
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(500);
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            assertThat(json.get("message").getAsString()).containsIgnoringCase("invalid page");
        }
    }
}
