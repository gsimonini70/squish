package com.lucsartech.squish.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lucsartech.squish.config.CompressionMode;
import com.lucsartech.squish.config.CompressionProfile;
import com.lucsartech.squish.config.SecurityConfig;
import com.lucsartech.squish.config.SquishProperties;
import com.lucsartech.squish.pipeline.ProgressTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Spring MVC slice tests for the read-only status / health / profile / metrics
 * JSON+text endpoints and the dashboard HTML page.
 *
 * <p>Replaces the old {@code MonitorServerTest}, which drove a standalone
 * {@code com.sun.net.httpserver} instance. The contract assertions are preserved
 * verbatim; only the transport (MockMvc instead of {@code java.net.http}) changed.
 *
 * <p>{@link SecurityConfig} is imported so the real public-GET rules apply: all
 * endpoints exercised here are public, so no authentication is needed.
 */
// spring.config.location points at a near-empty file so the real application.yml
// is NOT loaded: its squish.* block would otherwise be re-bound onto the
// programmatic SquishProperties below (SquishProperties is @ConfigurationProperties).
@WebMvcTest(controllers = {StatusController.class, MetricsController.class, PageController.class},
        properties = "spring.config.location=classpath:/http-slice-test.yml")
@Import({SecurityConfig.class, StatusApiTest.Beans.class})
@DisplayName("Status / Health / Profiles / Metrics API")
class StatusApiTest {

    private static final Gson GSON = new Gson();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProgressTracker tracker;

    @TestConfiguration
    static class Beans {
        @Bean
        ProgressTracker progressTracker() {
            return new ProgressTracker();
        }

        @Bean
        SquishProperties squishProperties() {
            var properties = new SquishProperties();
            properties.setMode(CompressionMode.MEDIUM);
            properties.setActiveProfile("test-profile");

            var profiles = new HashMap<String, CompressionProfile>();
            var testProfile = new CompressionProfile("test-profile", CompressionMode.MEDIUM);
            testProfile.setDescription("Test profile for unit tests");
            profiles.put("test-profile", testProfile);

            var aggressiveProfile = new CompressionProfile("aggressive", CompressionMode.AGGRESSIVE);
            aggressiveProfile.setDescription("Aggressive compression");
            profiles.put("aggressive", aggressiveProfile);

            properties.setProfiles(profiles);
            return properties;
        }
    }

    private MockHttpServletResponse getResponse(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn().getResponse();
    }

    // The ProgressTracker bean is shared across methods (the slice context is cached).
    // markCompleted() is a one-way latch, so healthUp (which needs RUNNING) must run
    // before healthCompleted; ordering makes that deterministic regardless of JUnit's
    // default method order. No other tracker mutation affects a different test.
    @Nested
    @DisplayName("Health endpoint")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class HealthEndpoint {

        @Test
        @Order(1)
        @DisplayName("should return UP status")
        void healthUp() throws Exception {
            var response = getResponse("/api/health");

            assertThat(response.getStatus()).isEqualTo(200);

            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            assertThat(json.get("status").getAsString()).isEqualTo("UP");
            assertThat(json.get("phase").getAsString()).isEqualTo("RUNNING");
            // Build identity is exposed on the lightweight health probe too.
            assertThat(json.get("version").getAsString()).isNotBlank();
            assertThat(json.get("buildNumber").getAsString()).isNotBlank();
        }

        @Test
        @Order(2)
        @DisplayName("should return COMPLETED when finished")
        void healthCompleted() throws Exception {
            tracker.markStarted();
            tracker.markCompleted();

            var response = getResponse("/api/health");
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);

            assertThat(json.get("phase").getAsString()).isEqualTo("COMPLETED");
        }
    }

    @Nested
    @DisplayName("Status endpoint")
    class StatusEndpoint {

        @Test
        @DisplayName("should return status JSON")
        void statusJson() throws Exception {
            var response = getResponse("/api/status");

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).contains("application/json");

            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            assertThat(json.has("data")).isTrue();
            assertThat(json.has("mode")).isTrue();
            assertThat(json.has("compressionMode")).isTrue();

            // Dashboard additions: active profile, on-demand counters, app version.
            assertThat(json.has("profile")).isTrue();
            assertThat(json.has("onDemand")).isTrue();
            assertThat(json.has("version")).isTrue();
            assertThat(json.getAsJsonObject("onDemand").has("calls")).isTrue();
        }

        @Test
        @DisplayName("should include system info")
        void includesSystemInfo() throws Exception {
            var response = getResponse("/api/status");
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);

            assertThat(json.has("system")).isTrue();
            var system = json.getAsJsonObject("system");
            assertThat(system.has("memUsed")).isTrue();
            assertThat(system.has("cpuPercent")).isTrue();
            assertThat(system.has("activeThreads")).isTrue();
        }

        @Test
        @DisplayName("should track progress")
        void tracksProgress() throws Exception {
            tracker.markStarted();
            tracker.setInitialStats(100, 1024 * 1024);

            var response = getResponse("/api/status");
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            var data = json.getAsJsonObject("data");

            assertThat(data.get("totalRecords").getAsLong()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("Profiles endpoint")
    class ProfilesEndpoint {

        @Test
        @DisplayName("should return available profiles")
        void listProfiles() throws Exception {
            var response = getResponse("/api/profiles");

            assertThat(response.getStatus()).isEqualTo(200);

            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            assertThat(json.has("profiles")).isTrue();
            assertThat(json.has("activeProfile")).isTrue();

            var profiles = json.getAsJsonArray("profiles");
            assertThat(profiles.size()).isEqualTo(2);

            assertThat(json.get("activeProfile").getAsString()).isEqualTo("test-profile");
        }

        @Test
        @DisplayName("profile should include all fields")
        void profileFields() throws Exception {
            var response = getResponse("/api/profiles");
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            var profiles = json.getAsJsonArray("profiles");

            // Find test-profile
            JsonObject testProfile = null;
            for (var element : profiles) {
                var profile = element.getAsJsonObject();
                if ("test-profile".equals(profile.get("name").getAsString())) {
                    testProfile = profile;
                    break;
                }
            }

            assertThat(testProfile).isNotNull();
            assertThat(testProfile.get("name").getAsString()).isEqualTo("test-profile");
            assertThat(testProfile.get("description").getAsString()).isEqualTo("Test profile for unit tests");
            assertThat(testProfile.get("mode").getAsString()).isEqualTo("MEDIUM");
            assertThat(testProfile.has("scaleFactor")).isTrue();
            assertThat(testProfile.has("jpegQuality")).isTrue();
            assertThat(testProfile.has("lossless")).isTrue();
            assertThat(testProfile.has("watermarkEnabled")).isTrue();
            assertThat(testProfile.has("pdfaEnabled")).isTrue();
        }
    }

    @Nested
    @DisplayName("Active profile endpoint")
    class ActiveProfileEndpoint {

        @Test
        @DisplayName("should return active profile")
        void activeProfile() throws Exception {
            var response = getResponse("/api/profile");

            assertThat(response.getStatus()).isEqualTo(200);

            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            assertThat(json.get("name").getAsString()).isEqualTo("test-profile");
            assertThat(json.get("mode").getAsString()).isEqualTo("MEDIUM");
        }
    }

    @Nested
    @DisplayName("Metrics endpoint")
    class MetricsEndpoint {

        @Test
        @DisplayName("should return Prometheus metrics")
        void prometheusMetrics() throws Exception {
            var response = getResponse("/metrics");

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).contains("text/plain");

            String body = response.getContentAsString();
            assertThat(body).contains("squish_");
        }
    }

    @Nested
    @DisplayName("Dashboard endpoint")
    class DashboardEndpoint {

        @Test
        @DisplayName("should return HTML dashboard")
        void htmlDashboard() throws Exception {
            var response = getResponse("/");

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).contains("text/html");

            String body = response.getContentAsString();
            assertThat(body).contains("<!DOCTYPE html>");
            assertThat(body).contains("Squish");
        }
    }
}
