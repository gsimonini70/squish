package com.lucsartech.squish.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lucsartech.squish.config.CompressionMode;
import com.lucsartech.squish.config.CompressionProfile;
import com.lucsartech.squish.config.SecurityConfig;
import com.lucsartech.squish.config.SquishProperties;
import com.lucsartech.squish.pipeline.ProgressTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Spring MVC slice tests for the configuration API ({@code /api/config}) and the
 * configuration HTML page ({@code /config}).
 *
 * <p>{@link SecurityConfig} is imported with security ENABLED: GET is public,
 * POST requires HTTP Basic. The security password is pinned so the write tests can
 * authenticate as {@code admin}.
 */
// Isolated config location so the real application.yml squish.* block does not
// re-bind the programmatic SquishProperties below.
@WebMvcTest(controllers = {ConfigApiController.class, PageController.class},
        properties = "spring.config.location=classpath:/http-slice-test.yml")
@Import({SecurityConfig.class, WebConfigTest.Beans.class})
@DisplayName("Web UI Configuration")
class WebConfigTest {

    private static final Gson GSON = new Gson();
    private static final String ADMIN_PASSWORD = "test-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SquishProperties properties;

    // The SquishProperties bean is a mutable singleton shared across all methods in
    // this class (the config slice is cached, and @Nested methods do not get a fresh
    // context). Reset the only mutated field before each test so a profile switch in
    // one test cannot leak into another - this runs before @Nested methods too.
    @BeforeEach
    void resetActiveProfile() {
        properties.setActiveProfile("office");
    }

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

            // Pin the credentials so the authenticated write tests are deterministic.
            props.getSecurity().setEnabled(true);
            props.getSecurity().setUsername("admin");
            props.getSecurity().setPassword(ADMIN_PASSWORD);

            var archival = new CompressionProfile("archival", CompressionMode.LOSSLESS);
            archival.setDescription("Archival quality - lossless compression");
            archival.setPdfaEnabled(true);
            archival.setPdfaConformance(CompressionProfile.PdfAConformance.PDF_A_2B);

            var office = new CompressionProfile("office", CompressionMode.MEDIUM);
            office.setDescription("Office documents - balanced compression");

            var web = new CompressionProfile("web", CompressionMode.AGGRESSIVE);
            web.setDescription("Web/email - maximum compression");
            web.setWatermarkEnabled(true);
            web.setWatermarkText("DRAFT");

            props.setProfiles(Map.of(
                    "archival", archival,
                    "office", office,
                    "web", web
            ));

            props.getWatchdog().setEnabled(false);
            return props;
        }
    }

    private MockHttpServletResponse getConfig(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn().getResponse();
    }

    @Nested
    @DisplayName("Config Page")
    class ConfigPage {

        @Test
        @DisplayName("should return HTML config page")
        void configPageReturnsHtml() throws Exception {
            var response = getConfig("/config");

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).contains("text/html");
            assertThat(response.getContentAsString()).contains("Configuration");
            assertThat(response.getContentAsString()).contains("Compression Profiles");
            assertThat(response.getContentAsString()).contains("/api/config");
        }

        @Test
        @DisplayName("config page should have navigation links")
        void configPageHasNavigation() throws Exception {
            var response = getConfig("/config");
            String body = response.getContentAsString();

            assertThat(body).contains("href=\"/\"");            // Dashboard link
            assertThat(body).contains("href=\"/api/config\"");  // API link
            assertThat(body).contains("href=\"/metrics\"");     // Metrics link
        }
    }

    @Nested
    @DisplayName("Config API - GET")
    class ConfigApiGet {

        @Test
        @DisplayName("should return current configuration")
        void getConfigReturnsJson() throws Exception {
            var response = getConfig("/api/config");

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).contains("application/json");

            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            assertThat(json.get("activeProfile").getAsString()).isEqualTo("office");
            assertThat(json.get("dryRun").getAsBoolean()).isFalse();
            assertThat(json.get("legacyMode").getAsString()).isEqualTo("MEDIUM");
            assertThat(json.get("watchdogEnabled").getAsBoolean()).isFalse();
        }

        @Test
        @DisplayName("should return all profiles")
        void getConfigReturnsProfiles() throws Exception {
            var response = getConfig("/api/config");
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            var profiles = json.getAsJsonObject("profiles");

            assertThat(profiles.has("archival")).isTrue();
            assertThat(profiles.has("office")).isTrue();
            assertThat(profiles.has("web")).isTrue();
        }

        @Test
        @DisplayName("should return profile details")
        void getConfigReturnsProfileDetails() throws Exception {
            var response = getConfig("/api/config");
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            var archival = json.getAsJsonObject("profiles").getAsJsonObject("archival");

            assertThat(archival.get("mode").getAsString()).isEqualTo("LOSSLESS");
            assertThat(archival.get("pdfaEnabled").getAsBoolean()).isTrue();
            assertThat(archival.get("pdfaConformance").getAsString()).isEqualTo("PDF_A_2B");
        }

        @Test
        @DisplayName("should return watermark details for profile")
        void getConfigReturnsWatermarkDetails() throws Exception {
            var response = getConfig("/api/config");
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            var web = json.getAsJsonObject("profiles").getAsJsonObject("web");

            assertThat(web.get("watermarkEnabled").getAsBoolean()).isTrue();
            assertThat(web.get("watermarkText").getAsString()).isEqualTo("DRAFT");
        }
    }

    @Nested
    @DisplayName("Config API - POST")
    class ConfigApiPost {

        @Test
        @DisplayName("should switch active profile")
        void switchProfile() throws Exception {
            var response = mockMvc.perform(post("/api/config")
                            .with(httpBasic("admin", ADMIN_PASSWORD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"activeProfile\": \"archival\"}"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            assertThat(json.get("success").getAsBoolean()).isTrue();
            assertThat(json.get("message").getAsString()).contains("archival");

            // Verify the change persisted
            assertThat(properties.getActiveProfile()).isEqualTo("archival");
        }

        @Test
        @DisplayName("should reject non-existent profile")
        void rejectNonExistentProfile() throws Exception {
            var response = mockMvc.perform(post("/api/config")
                            .with(httpBasic("admin", ADMIN_PASSWORD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"activeProfile\": \"nonexistent\"}"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(400);
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            assertThat(json.get("message").getAsString()).contains("not found");
        }

        @Test
        @DisplayName("should reject invalid JSON")
        void rejectInvalidJson() throws Exception {
            var response = mockMvc.perform(post("/api/config")
                            .with(httpBasic("admin", ADMIN_PASSWORD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{invalid json"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(400);
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            assertThat(json.get("message").getAsString()).containsIgnoringCase("invalid");
        }

        @Test
        @DisplayName("POST without credentials should be rejected (security regression)")
        void rejectUnauthenticatedPost() throws Exception {
            var response = mockMvc.perform(post("/api/config")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"activeProfile\": \"archival\"}"))
                    .andReturn().getResponse();

            // Basic auth challenge -> 401; either 401 or 403 documents the endpoint is protected.
            assertThat(response.getStatus()).isIn(401, 403);

            // And the write must NOT have taken effect.
            assertThat(properties.getActiveProfile()).isEqualTo("office");
        }
    }

    @Nested
    @DisplayName("CORS behaviour (no wildcard)")
    class CorsBehaviour {

        /**
         * The legacy server answered a CORS pre-flight with 204 and
         * {@code Access-Control-Allow-Methods: ...POST...}. CORS is now disabled by
         * default (no allow-listed origins), so no wildcard origin header is emitted.
         */
        @Test
        @DisplayName("cross-origin GET does not receive a wildcard Access-Control-Allow-Origin")
        void noWildcardCorsHeader() throws Exception {
            var response = mockMvc.perform(get("/api/config")
                            .header("Origin", "http://evil.example.com"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNotEqualTo("*");
        }
    }

    @Nested
    @DisplayName("Profile Switching Integration")
    class ProfileSwitchingIntegration {

        @Test
        @DisplayName("should switch from office to web profile")
        void switchOfficeToWeb() throws Exception {
            // Initial state
            assertThat(properties.getActiveProfile()).isEqualTo("office");

            // Switch to web
            mockMvc.perform(post("/api/config")
                    .with(httpBasic("admin", ADMIN_PASSWORD))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"activeProfile\": \"web\"}"));

            // Verify new profile
            var activeProfile = properties.getActiveCompressionProfile();
            assertThat(activeProfile.getName()).isEqualTo("web");
            assertThat(activeProfile.getMode()).isEqualTo(CompressionMode.AGGRESSIVE);
            assertThat(activeProfile.isWatermarkEnabled()).isTrue();
        }

        @Test
        @DisplayName("config GET should reflect profile changes")
        void getReflectsChanges() throws Exception {
            // Switch profile directly
            properties.setActiveProfile("archival");

            // GET should show new active profile
            var response = getConfig("/api/config");
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);

            assertThat(json.get("activeProfile").getAsString()).isEqualTo("archival");
        }
    }
}
