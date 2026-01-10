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
    private final Query query = new Query();
    private final Pipeline pipeline = new Pipeline();
    private final Http http = new Http();
    private final Watchdog watchdog = new Watchdog();
    private final Email email = new Email();
    private final Report report = new Report();

    // Getters and setters
    public CompressionMode getMode() { return mode; }
    public void setMode(CompressionMode mode) { this.mode = mode; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    public Database getDatabase() { return database; }
    public Query getQuery() { return query; }
    public Pipeline getPipeline() { return pipeline; }
    public Http getHttp() { return http; }
    public Watchdog getWatchdog() { return watchdog; }
    public Email getEmail() { return email; }
    public Report getReport() { return report; }

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
     * Query configuration - table/column names and filters.
     */
    public static class Query {
        // Table names
        private String masterTable = "OTTICA";
        private String detailTable = "OTTICAI";
        private String trackingTable = "SQUISH_PROCESSED";

        // Column names - master table
        private String idColumn = "OTT_ID";
        private String filenameColumn = "OTT_NOME_FILE";

        // Column names - detail table (composite PK: detailIdColumn + detailCtrColumn)
        private String detailIdColumn = "OTTI_ID";
        private String detailCtrColumn = "OTTI_CTR";
        private String dataColumn = "OTTI_DATA";

        // Generic filter condition for master table (WHERE clause fragment)
        // Examples: "OTT_TIPO_DOC = '001030'" or "OTT_TIPO_DOC IN ('001030','001031') AND OTT_STATUS = 'A'"
        private String masterTableFilter = "OTT_TIPO_DOC = '001030'";

        public String getMasterTable() { return masterTable; }
        public void setMasterTable(String masterTable) { this.masterTable = masterTable; }

        public String getDetailTable() { return detailTable; }
        public void setDetailTable(String detailTable) { this.detailTable = detailTable; }

        public String getTrackingTable() { return trackingTable; }
        public void setTrackingTable(String trackingTable) { this.trackingTable = trackingTable; }

        public String getIdColumn() { return idColumn; }
        public void setIdColumn(String idColumn) { this.idColumn = idColumn; }

        public String getFilenameColumn() { return filenameColumn; }
        public void setFilenameColumn(String filenameColumn) { this.filenameColumn = filenameColumn; }

        public String getDetailIdColumn() { return detailIdColumn; }
        public void setDetailIdColumn(String detailIdColumn) { this.detailIdColumn = detailIdColumn; }

        public String getDetailCtrColumn() { return detailCtrColumn; }
        public void setDetailCtrColumn(String detailCtrColumn) { this.detailCtrColumn = detailCtrColumn; }

        public String getDataColumn() { return dataColumn; }
        public void setDataColumn(String dataColumn) { this.dataColumn = dataColumn; }

        public String getMasterTableFilter() { return masterTableFilter; }
        public void setMasterTableFilter(String masterTableFilter) { this.masterTableFilter = masterTableFilter; }
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
        private boolean starttls = true;
        private String sslProtocols = "TLSv1.2,TLSv1.3";
        private boolean trustAllCerts = false;
        private int connectionTimeout = 10000;
        private int readTimeout = 30000;
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

        public boolean isStarttls() { return starttls; }
        public void setStarttls(boolean starttls) { this.starttls = starttls; }

        public String getSslProtocols() { return sslProtocols; }
        public void setSslProtocols(String sslProtocols) { this.sslProtocols = sslProtocols; }

        public boolean isTrustAllCerts() { return trustAllCerts; }
        public void setTrustAllCerts(boolean trustAllCerts) { this.trustAllCerts = trustAllCerts; }

        public int getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(int connectionTimeout) { this.connectionTimeout = connectionTimeout; }

        public int getReadTimeout() { return readTimeout; }
        public void setReadTimeout(int readTimeout) { this.readTimeout = readTimeout; }

        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }

        public List<String> getTo() { return to; }
        public void setTo(List<String> to) { this.to = to; }

        public boolean hasAuth() {
            return smtpUser != null && !smtpUser.isBlank();
        }
    }

    /**
     * HTTP monitoring server configuration.
     */
    public static class Http {
        private int port = 8080;
        private boolean sslEnabled = false;
        private String keystorePath;
        private String keystorePassword;
        private String keystoreType = "PKCS12";
        private String sslProtocol = "TLSv1.3";

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public boolean isSslEnabled() { return sslEnabled; }
        public void setSslEnabled(boolean sslEnabled) { this.sslEnabled = sslEnabled; }

        public String getKeystorePath() { return keystorePath; }
        public void setKeystorePath(String keystorePath) { this.keystorePath = keystorePath; }

        public String getKeystorePassword() { return keystorePassword; }
        public void setKeystorePassword(String keystorePassword) { this.keystorePassword = keystorePassword; }

        public String getKeystoreType() { return keystoreType; }
        public void setKeystoreType(String keystoreType) { this.keystoreType = keystoreType; }

        public String getSslProtocol() { return sslProtocol; }
        public void setSslProtocol(String sslProtocol) { this.sslProtocol = sslProtocol; }
    }

    /**
     * Report generation configuration.
     */
    public static class Report {
        private String directory = "reports";
        private boolean enabled = true;

        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
