package com.lucsartech.squish.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.UUID;

/**
 * Spring Security for the monitoring dashboard and REST API.
 *
 * <p>Read endpoints (dashboard, status, metrics) are public; write endpoints
 * (compress, thumbnail, config POST) require HTTP Basic auth. Security can be
 * disabled entirely via {@code squish.security.enabled=false} for tests or
 * closed-LAN deployments.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String[] PUBLIC_GET = {
            "/", "/config",
            "/api/status", "/api/health", "/api/profiles", "/api/profile", "/api/config",
            "/metrics"
    };
    private static final String[] PUBLIC_STATIC = {"/css/**", "/js/**"};

    private final SquishProperties properties;

    public SecurityConfig(SquishProperties properties) {
        this.properties = properties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var security = properties.getSecurity();

        // Stateless API secured (or not) by Basic auth: CSRF tokens do not apply.
        http.csrf(AbstractHttpConfigurer::disable);
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()));
        http.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!security.isEnabled()) {
            log.warn("Spring Security is DISABLED (squish.security.enabled=false) - all endpoints are public");
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_STATIC).permitAll()
                .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/config", "/api/compress", "/api/thumbnail").authenticated()
                .anyRequest().authenticated());
        http.httpBasic(org.springframework.security.config.Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public InMemoryUserDetailsManager userDetailsManager(PasswordEncoder passwordEncoder) {
        var security = properties.getSecurity();
        var username = security.getUsername();
        var password = security.getPassword();

        if (password == null || password.isBlank()) {
            password = UUID.randomUUID().toString();
            log.warn("Generated security password for user '{}': {} "
                    + "- set squish.security.password to make it stable", username, password);
        }

        UserDetails user = User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    /**
     * CORS policy driven by {@code squish.http.cors-allowed-origins}. No wildcard:
     * an empty list means no cross-origin headers are emitted at all.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var source = new UrlBasedCorsConfigurationSource();
        var origins = properties.getHttp().getCorsAllowedOrigins();

        if (origins == null || origins.isEmpty()) {
            return source; // no registered mappings -> returns null -> CORS not applied
        }

        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.copyOf(origins));
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Authorization"));
        config.setExposedHeaders(List.of(
                "X-Original-Size", "X-Compressed-Size", "X-Savings-Percent", "X-Duration-Ms"));
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
