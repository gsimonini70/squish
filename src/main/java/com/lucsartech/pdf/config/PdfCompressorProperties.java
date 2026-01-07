package com.lucsartech.pdf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for PDF Compressor.
 * Mapped from application.yml under the "compressor" prefix.
 */
@ConfigurationProperties(prefix = "compressor")
@Validated
public class PdfCompressorProperties {

    private CompressionMode mode = CompressionMode.MEDIUM;
    private boolean dryRun = false;

    private final Database database = new Database();
    private final Pipeline pipeline = new Pipeline();
    private final Http http = new Http();
    private final Watchdog watchdog = new Watchdog();
    private final Email email = new Email();

    // Getters and setters
    public CompressionMode getMode() { return mode; }
    public void setMode(CompressionMode mode) { this.mode = mode; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    public Database getDatabase() { return database; }
    public Pipeline getPipeline() { return pipeline; }
    public Http getHttp() { return http; }
    public Watchdog getWatchdog() { return watchdog; }
    public Email getEmail() { return email; }

    /**
     * Database configuration.
     */
    public static class Database {
        private String jdbcUrl;
        private String username;
        private String password;
        private int maxPoolSize = 12;

        public String getJdbcUrl() { return jdbcUrl; }
        public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
    }

    /**
     * Pipeline processing configuration.
     */
    public static class Pipeline {
        private int workerThreads = 8;
        private long idFrom = 0;
        private long idTo = 0; // 0 = no limit
        private int fetchSize = 200;
        private int batchSize = 200;
        private int queueCapacity = 500;
        private long throttleMillis = 0;

        public int getWorkerThreads() { return workerThreads; }
        public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }

        public long getIdFrom() { return idFrom; }
        public void setIdFrom(long idFrom) { this.idFrom = idFrom; }

        public long getIdTo() { return idTo; }
        public void setIdTo(long idTo) { this.idTo = idTo; }

        public int getFetchSize() { return fetchSize; }
        public void setFetchSize(int fetchSize) { this.fetchSize = fetchSize; }

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

        public long getThrottleMillis() { return throttleMillis; }
        public void setThrottleMillis(long throttleMillis) { this.throttleMillis = throttleMillis; }

        public boolean hasUpperBound() {
            return idTo > 0;
        }
    }

    /**
     * HTTP monitoring server configuration.
     */
    public static class Http {
        private int port = 8080;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    /**
     * Watchdog (continuous monitoring) configuration.
     */
    public static class Watchdog {
        private boolean enabled = false;
        private int pollIntervalSeconds = 60;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getPollIntervalSeconds() { return pollIntervalSeconds; }
        public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }
    }

    /**
     * Email notification configuration.
     */
    public static class Email {
        private boolean enabled = false;
        private String smtpHost;
        private int smtpPort = 587;
        private String smtpUser;
        private String smtpPassword;
        private boolean ssl = true;
        private String from;
        private List<String> to = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getSmtpHost() { return smtpHost; }
        public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }

        public int getSmtpPort() { return smtpPort; }
        public void setSmtpPort(int smtpPort) { this.smtpPort = smtpPort; }

        public String getSmtpUser() { return smtpUser; }
        public void setSmtpUser(String smtpUser) { this.smtpUser = smtpUser; }

        public String getSmtpPassword() { return smtpPassword; }
        public void setSmtpPassword(String smtpPassword) { this.smtpPassword = smtpPassword; }

        public boolean isSsl() { return ssl; }
        public void setSsl(boolean ssl) { this.ssl = ssl; }

        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }

        public List<String> getTo() { return to; }
        public void setTo(List<String> to) { this.to = to; }

        public boolean hasAuth() {
            return smtpUser != null && !smtpUser.isBlank();
        }
    }
}
