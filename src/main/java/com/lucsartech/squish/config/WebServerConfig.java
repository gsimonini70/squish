package com.lucsartech.squish.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Applies {@code squish.http.*} to the embedded servlet container: port and,
 * when enabled, TLS.
 *
 * <p>If SSL is enabled but the keystore is missing or unreadable the application
 * fails fast at startup, rather than silently downgrading to plaintext HTTP.
 */
@Component
public class WebServerConfig implements WebServerFactoryCustomizer<ConfigurableWebServerFactory> {

    private static final Logger log = LoggerFactory.getLogger(WebServerConfig.class);

    private final SquishProperties properties;

    public WebServerConfig(SquishProperties properties) {
        this.properties = properties;
    }

    @Override
    public void customize(ConfigurableWebServerFactory factory) {
        var http = properties.getHttp();
        factory.setPort(http.getPort());

        if (!http.isSslEnabled()) {
            log.info("Web server configured on port {} (HTTP)", http.getPort());
            return;
        }

        var keystorePath = http.getKeystorePath();
        if (keystorePath == null || keystorePath.isBlank()) {
            throw new IllegalStateException(
                    "squish.http.ssl-enabled=true but squish.http.keystore-path is not set. "
                            + "Provide a valid keystore or disable SSL.");
        }
        if (!Files.isReadable(Path.of(keystorePath))) {
            throw new IllegalStateException(
                    "squish.http.ssl-enabled=true but keystore is not readable at: " + keystorePath
                            + ". Refusing to start in plaintext HTTP (previous silent fallback was a security defect).");
        }

        var ssl = new Ssl();
        ssl.setEnabled(true);
        ssl.setKeyStore(keystorePath);
        ssl.setKeyStorePassword(http.getKeystorePassword());
        ssl.setKeyStoreType(http.getKeystoreType());
        if (http.getSslProtocol() != null && !http.getSslProtocol().isBlank()) {
            ssl.setProtocol(http.getSslProtocol());
            ssl.setEnabledProtocols(new String[]{http.getSslProtocol()});
        }
        factory.setSsl(ssl);

        log.info("Web server configured on port {} (HTTPS, {})", http.getPort(), http.getSslProtocol());
    }
}
