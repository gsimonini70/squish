package com.lucsartech.squish.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lucsartech.squish.config.SecurityConfig;
import com.lucsartech.squish.config.SquishProperties;
import com.lucsartech.squish.pipeline.ScopeState;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Spring MVC slice tests for {@code /api/scope}. {@code GET} is public; {@code POST} is a write
 * endpoint and requires HTTP Basic auth (see {@link SecurityConfig}). {@link ScopeState} is a real
 * singleton bean in the slice, so it is reset before each test to keep them independent.
 */
@WebMvcTest(controllers = ScopeController.class,
        properties = "spring.config.location=classpath:/http-slice-test.yml")
@Import({SecurityConfig.class, ScopeApiTest.Beans.class})
@DisplayName("Scope API")
class ScopeApiTest {

    private static final Gson GSON = new Gson();
    private static final String ADMIN_PASSWORD = "test-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScopeState scopeState;

    @BeforeEach
    void resetOverride() {
        scopeState.clear("test reset");
    }

    @TestConfiguration
    static class Beans {
        @Bean
        ScopeState scopeState() {
            return new ScopeState();
        }

        @Bean
        SquishProperties squishProperties() {
            var props = new SquishProperties();
            props.getSecurity().setEnabled(true);
            props.getSecurity().setUsername("admin");
            props.getSecurity().setPassword(ADMIN_PASSWORD);

            props.getWatchdog().setEnabled(true);
            props.getWatchdog().setPollIntervalSeconds(30);

            props.getQuery().setMasterTableFilter("OTT_TIPO_DOC = '001030'");
            props.getQuery().setDocTypeColumn("OTT_TIPO_DOC");
            props.getQuery().setAllowedDocTypes(List.of("001030", "001031"));
            return props;
        }
    }

    @Nested
    @DisplayName("GET /api/scope")
    class GetScope {

        @Test
        @DisplayName("is public and reports the configured baseline when no override is active")
        void reportsBaseline() throws Exception {
            var response = mockMvc.perform(get("/api/scope")).andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            var json = GSON.fromJson(response.getContentAsString(), JsonObject.class);
            assertThat(json.get("active").getAsBoolean()).isFalse();
            assertThat(json.get("watchdogEnabled").getAsBoolean()).isTrue();
            assertThat(json.get("pollIntervalSeconds").getAsInt()).isEqualTo(30);
            assertThat(json.get("effectiveFilter").getAsString()).isEqualTo("OTT_TIPO_DOC = '001030'");
            assertThat(json.getAsJsonArray("allowedDocTypes")).hasSize(2);
        }
    }

    @Nested
    @DisplayName("POST /api/scope")
    class PostScope {

        @Test
        @DisplayName("without credentials is rejected (security regression)")
        void rejectUnauthenticated() throws Exception {
            var response = mockMvc.perform(post("/api/scope")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"active\":true,\"idFrom\":10,\"idTo\":20}"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isIn(401, 403);
        }

        @Test
        @DisplayName("applies a valid override and GET then reflects it")
        void applyValidOverride() throws Exception {
            var apply = mockMvc.perform(post("/api/scope")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"active\":true,\"idFrom\":10,\"idTo\":20,\"docType\":\"001031\",\"autoRevert\":true}")
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(apply.getStatus()).isEqualTo(200);
            assertThat(GSON.fromJson(apply.getContentAsString(), JsonObject.class)
                    .get("success").getAsBoolean()).isTrue();

            var json = GSON.fromJson(
                    mockMvc.perform(get("/api/scope")).andReturn().getResponse().getContentAsString(),
                    JsonObject.class);
            assertThat(json.get("active").getAsBoolean()).isTrue();
            assertThat(json.get("idFrom").getAsLong()).isEqualTo(10);
            assertThat(json.get("idTo").getAsLong()).isEqualTo(20);
            assertThat(json.get("docType").getAsString()).isEqualTo("001031");
            assertThat(json.get("autoRevert").getAsBoolean()).isTrue();
            assertThat(json.get("effectiveFilter").getAsString()).isEqualTo("OTT_TIPO_DOC = '001031'");
        }

        @Test
        @DisplayName("rejects a doc-type that is not on the allow-list (400)")
        void rejectDisallowedDocType() throws Exception {
            var response = mockMvc.perform(post("/api/scope")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"active\":true,\"docType\":\"x' OR '1'='1\"}")
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(GSON.fromJson(response.getContentAsString(), JsonObject.class)
                    .get("message").getAsString()).containsIgnoringCase("not allowed");
            // And nothing was activated.
            assertThat(scopeState.snapshot().active()).isFalse();
        }

        @Test
        @DisplayName("rejects an inverted id range (400)")
        void rejectInvertedRange() throws Exception {
            var response = mockMvc.perform(post("/api/scope")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"active\":true,\"idFrom\":900,\"idTo\":100}")
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(400);
        }

        @Test
        @DisplayName("clears an active override with active=false")
        void clearOverride() throws Exception {
            scopeState.apply(1, 2, null, false, "seed");

            var response = mockMvc.perform(post("/api/scope")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"active\":false}")
                            .with(httpBasic("admin", ADMIN_PASSWORD)))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(scopeState.snapshot().active()).isFalse();
        }
    }
}
