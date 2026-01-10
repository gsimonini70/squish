package com.lucsartech.pdf.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lucsartech.pdf.config.CompressionMode;
import com.lucsartech.pdf.config.PdfCompressorProperties;
import com.lucsartech.pdf.pipeline.ProgressTracker;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.Executors;

/**
 * Modern HTTP monitoring server with JSON API and beautiful dashboard.
 */
public final class MonitorServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MonitorServer.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ProgressTracker tracker;
    private final boolean dryRun;
    private final boolean watchMode;
    private final CompressionMode compressionMode;
    private final PdfCompressorProperties.Http httpConfig;
    private HttpServer server;

    public MonitorServer(ProgressTracker tracker, boolean dryRun, CompressionMode compressionMode) {
        this(tracker, dryRun, false, compressionMode, null);
    }

    public MonitorServer(ProgressTracker tracker, boolean dryRun, boolean watchMode, CompressionMode compressionMode) {
        this(tracker, dryRun, watchMode, compressionMode, null);
    }

    public MonitorServer(ProgressTracker tracker, boolean dryRun, boolean watchMode,
                         CompressionMode compressionMode, PdfCompressorProperties.Http httpConfig) {
        this.tracker = tracker;
        this.dryRun = dryRun;
        this.watchMode = watchMode;
        this.compressionMode = compressionMode;
        this.httpConfig = httpConfig;
    }

    public void start(int port) throws IOException {
        boolean sslEnabled = httpConfig != null && httpConfig.isSslEnabled();

        if (sslEnabled) {
            startHttpsServer(port);
        } else {
            startHttpServer(port);
        }
    }

    private void startHttpServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        configureContexts(server);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        log.info("Monitor server started at http://localhost:{}", port);
    }

    private void startHttpsServer(int port) throws IOException {
        try {
            // Load keystore
            KeyStore keyStore = KeyStore.getInstance(httpConfig.getKeystoreType());
            char[] password = httpConfig.getKeystorePassword().toCharArray();

            try (FileInputStream fis = new FileInputStream(httpConfig.getKeystorePath())) {
                keyStore.load(fis, password);
            }

            // Initialize key manager
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);

            // Initialize trust manager
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            // Create SSL context
            SSLContext sslContext = SSLContext.getInstance(httpConfig.getSslProtocol());
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

            // Create HTTPS server
            HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters sslParams = sslContext.getDefaultSSLParameters();
                    sslParams.setProtocols(new String[]{httpConfig.getSslProtocol()});
                    params.setSSLParameters(sslParams);
                }
            });

            configureContexts(httpsServer);
            httpsServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            httpsServer.start();

            server = httpsServer;
            log.info("Monitor server started at https://localhost:{} (TLS {})", port, httpConfig.getSslProtocol());

        } catch (Exception e) {
            log.error("Failed to start HTTPS server, falling back to HTTP", e);
            startHttpServer(port);
        }
    }

    private void configureContexts(HttpServer httpServer) {
        httpServer.createContext("/", this::handleDashboard);
        httpServer.createContext("/api/status", this::handleStatus);
        httpServer.createContext("/api/health", this::handleHealth);
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        var snapshot = tracker.snapshot();
        String modeStr = dryRun ? "DRY-RUN" : "NORMAL";
        if (watchMode) {
            modeStr = dryRun ? "WATCHDOG (DRY-RUN)" : "WATCHDOG";
        }
        var system = getSystemInfo();
        var activity = tracker.recentActivity();
        var response = new StatusResponse(snapshot, modeStr, compressionMode.name(), watchMode, system, activity);

        sendJson(exchange, response);
    }

    private SystemInfo getSystemInfo() {
        Runtime rt = Runtime.getRuntime();
        long maxMemory = rt.maxMemory();
        long totalMemory = rt.totalMemory();
        long freeMemory = rt.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        double memoryPercent = maxMemory > 0 ? (double) usedMemory / maxMemory * 100.0 : 0;
        int activeThreads = java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount();

        // CPU usage via OperatingSystemMXBean
        double cpuPercent = 0;
        var osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
            cpuPercent = sunOsBean.getProcessCpuLoad() * 100.0;
            if (cpuPercent < 0) cpuPercent = 0; // -1 means not available
        }
        int availableProcessors = osBean.getAvailableProcessors();

        return new SystemInfo(usedMemory, totalMemory, maxMemory, freeMemory, memoryPercent,
                              activeThreads, cpuPercent, availableProcessors);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        var health = new HealthResponse("UP", tracker.isCompleted() ? "COMPLETED" : "RUNNING");
        sendJson(exchange, health);
    }

    private void handleDashboard(HttpExchange exchange) throws IOException {
        String html = generateDashboardHtml();
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJson(HttpExchange exchange, Object data) throws IOException {
        String json = GSON.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(1);
            log.info("Monitor server stopped");
        }
    }

    private record StatusResponse(ProgressTracker.Snapshot data, String mode, String compressionMode,
                                     boolean watchMode, SystemInfo system,
                                     java.util.List<ProgressTracker.ActivityEntry> activity) {}
    private record HealthResponse(String status, String phase) {}
    private record SystemInfo(long memUsed, long memTotal, long memMax, long memFree, double memPercent,
                              int activeThreads, double cpuPercent, int cpuCores) {}

    private String generateDashboardHtml() {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Squish - PDF Compression Engine</title>
                <style>
                    :root {
                        --bg-primary: #0f0f23;
                        --bg-secondary: #1a1a2e;
                        --bg-card: rgba(30, 30, 50, 0.7);
                        --border-color: rgba(255, 255, 255, 0.1);
                        --text-primary: #e4e4e7;
                        --text-secondary: #a1a1aa;
                        --accent-blue: #3b82f6;
                        --accent-green: #22c55e;
                        --accent-purple: #8b5cf6;
                        --accent-orange: #f59e0b;
                        --accent-red: #ef4444;
                        --gradient-1: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        --gradient-2: linear-gradient(135deg, #22c55e 0%, #16a34a 100%);
                    }

                    * { box-sizing: border-box; margin: 0; padding: 0; }

                    body {
                        font-family: 'SF Pro Display', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background: var(--bg-primary);
                        color: var(--text-primary);
                        min-height: 100vh;
                        overflow-x: hidden;
                    }

                    /* Animated background */
                    .bg-animation {
                        position: fixed;
                        top: 0;
                        left: 0;
                        width: 100%;
                        height: 100%;
                        z-index: -1;
                        background:
                            radial-gradient(ellipse at 20% 20%, rgba(59, 130, 246, 0.15) 0%, transparent 50%),
                            radial-gradient(ellipse at 80% 80%, rgba(139, 92, 246, 0.15) 0%, transparent 50%),
                            radial-gradient(ellipse at 50% 50%, rgba(34, 197, 94, 0.08) 0%, transparent 60%);
                        animation: pulse 8s ease-in-out infinite;
                    }

                    @keyframes pulse {
                        0%, 100% { opacity: 1; }
                        50% { opacity: 0.7; }
                    }

                    .container {
                        max-width: 1400px;
                        margin: 0 auto;
                        padding: 2rem;
                    }

                    /* Header */
                    .header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 2rem;
                        flex-wrap: wrap;
                        gap: 1rem;
                    }

                    .header h1 {
                        font-size: 2rem;
                        font-weight: 700;
                        background: var(--gradient-1);
                        -webkit-background-clip: text;
                        -webkit-text-fill-color: transparent;
                        background-clip: text;
                    }

                    .badges {
                        display: flex;
                        gap: 0.75rem;
                        align-items: center;
                    }

                    .badge {
                        padding: 0.5rem 1rem;
                        border-radius: 9999px;
                        font-size: 0.75rem;
                        font-weight: 600;
                        text-transform: uppercase;
                        letter-spacing: 0.05em;
                    }

                    .badge-mode {
                        background: rgba(34, 197, 94, 0.2);
                        color: var(--accent-green);
                        border: 1px solid rgba(34, 197, 94, 0.3);
                    }

                    .badge-mode.dry-run {
                        background: rgba(245, 158, 11, 0.2);
                        color: var(--accent-orange);
                        border: 1px solid rgba(245, 158, 11, 0.3);
                    }

                    .badge-status {
                        background: rgba(59, 130, 246, 0.2);
                        color: var(--accent-blue);
                        border: 1px solid rgba(59, 130, 246, 0.3);
                    }

                    .badge-status.completed {
                        background: rgba(139, 92, 246, 0.2);
                        color: var(--accent-purple);
                        border: 1px solid rgba(139, 92, 246, 0.3);
                    }

                    .badge-compression {
                        background: rgba(236, 72, 153, 0.2);
                        color: #ec4899;
                        border: 1px solid rgba(236, 72, 153, 0.3);
                    }
                    .badge-compression.lossless {
                        background: rgba(34, 197, 94, 0.2);
                        color: var(--accent-green);
                        border: 1px solid rgba(34, 197, 94, 0.3);
                    }
                    .badge-compression.medium {
                        background: rgba(245, 158, 11, 0.2);
                        color: var(--accent-orange);
                        border: 1px solid rgba(245, 158, 11, 0.3);
                    }
                    .badge-compression.aggressive {
                        background: rgba(239, 68, 68, 0.2);
                        color: var(--accent-red);
                        border: 1px solid rgba(239, 68, 68, 0.3);
                    }

                    .elapsed {
                        font-size: 0.875rem;
                        color: var(--text-secondary);
                    }

                    /* Cards */
                    .card {
                        background: var(--bg-card);
                        backdrop-filter: blur(20px);
                        -webkit-backdrop-filter: blur(20px);
                        border: 1px solid var(--border-color);
                        border-radius: 1.5rem;
                        padding: 1.5rem;
                        transition: all 0.3s ease;
                    }

                    .card:hover {
                        border-color: rgba(255, 255, 255, 0.2);
                        transform: translateY(-2px);
                    }

                    .card-header {
                        font-size: 0.75rem;
                        font-weight: 600;
                        text-transform: uppercase;
                        letter-spacing: 0.1em;
                        color: var(--text-secondary);
                        margin-bottom: 0.75rem;
                    }

                    .card-value {
                        font-size: 2rem;
                        font-weight: 700;
                        line-height: 1.2;
                    }

                    .card-sub {
                        font-size: 0.875rem;
                        color: var(--text-secondary);
                        margin-top: 0.5rem;
                    }

                    /* Progress Section */
                    .progress-section {
                        margin-bottom: 2rem;
                    }

                    .progress-card {
                        background: linear-gradient(135deg, rgba(59, 130, 246, 0.1) 0%, rgba(139, 92, 246, 0.1) 100%);
                    }

                    .progress-header {
                        display: flex;
                        justify-content: space-between;
                        align-items: baseline;
                        margin-bottom: 1rem;
                    }

                    .progress-percent {
                        font-size: 3rem;
                        font-weight: 800;
                        background: var(--gradient-1);
                        -webkit-background-clip: text;
                        -webkit-text-fill-color: transparent;
                        background-clip: text;
                    }

                    .progress-bar-container {
                        height: 1.5rem;
                        background: rgba(255, 255, 255, 0.1);
                        border-radius: 9999px;
                        overflow: hidden;
                        position: relative;
                    }

                    .progress-bar {
                        height: 100%;
                        background: var(--gradient-1);
                        border-radius: 9999px;
                        transition: width 0.5s ease-out;
                        position: relative;
                    }

                    .progress-bar::after {
                        content: '';
                        position: absolute;
                        top: 0;
                        left: 0;
                        right: 0;
                        bottom: 0;
                        background: linear-gradient(90deg, transparent, rgba(255,255,255,0.3), transparent);
                        animation: shimmer 2s infinite;
                    }

                    @keyframes shimmer {
                        0% { transform: translateX(-100%); }
                        100% { transform: translateX(100%); }
                    }

                    /* Grid layouts */
                    .grid {
                        display: grid;
                        gap: 1.5rem;
                    }

                    .grid-2 { grid-template-columns: repeat(2, 1fr); }
                    .grid-3 { grid-template-columns: repeat(3, 1fr); }
                    .grid-4 { grid-template-columns: repeat(4, 1fr); }
                    .grid-5 { grid-template-columns: repeat(5, 1fr); }

                    @media (max-width: 1024px) {
                        .grid-5 { grid-template-columns: repeat(3, 1fr); }
                        .grid-4 { grid-template-columns: repeat(2, 1fr); }
                        .grid-3 { grid-template-columns: repeat(2, 1fr); }
                    }

                    @media (max-width: 640px) {
                        .grid-5, .grid-4, .grid-3, .grid-2 { grid-template-columns: 1fr; }
                    }

                    /* Size comparison */
                    .size-comparison {
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                        gap: 1rem;
                        margin-top: 1.5rem;
                        padding: 1.5rem;
                        background: rgba(0, 0, 0, 0.3);
                        border-radius: 1rem;
                    }

                    .size-item {
                        text-align: center;
                        flex: 1;
                    }

                    .size-label {
                        font-size: 0.7rem;
                        text-transform: uppercase;
                        letter-spacing: 0.05em;
                        color: var(--text-secondary);
                        margin-bottom: 0.5rem;
                    }

                    .size-value {
                        font-size: 1.25rem;
                        font-weight: 700;
                    }

                    .size-value.blue { color: var(--accent-blue); }
                    .size-value.green { color: var(--accent-green); }
                    .size-value.purple { color: var(--accent-purple); }

                    .arrow {
                        color: var(--text-secondary);
                        font-size: 1.5rem;
                    }

                    /* Compression gauge */
                    .gauge-container {
                        position: relative;
                        width: 120px;
                        height: 60px;
                        margin: 0.5rem auto;
                    }

                    .gauge-bg {
                        position: absolute;
                        width: 120px;
                        height: 60px;
                        border-radius: 60px 60px 0 0;
                        background: rgba(255, 255, 255, 0.1);
                        overflow: hidden;
                    }

                    .gauge-fill {
                        position: absolute;
                        bottom: 0;
                        left: 0;
                        width: 120px;
                        height: 60px;
                        border-radius: 60px 60px 0 0;
                        background: var(--gradient-2);
                        transform-origin: bottom center;
                        transition: transform 0.5s ease-out;
                    }

                    /* Log section */
                    .log-section {
                        margin-top: 2rem;
                    }

                    .log-content {
                        font-family: 'SF Mono', 'Fira Code', monospace;
                        font-size: 0.75rem;
                        background: rgba(0, 0, 0, 0.4);
                        border-radius: 0.75rem;
                        padding: 1rem;
                        max-height: 200px;
                        overflow: auto;
                        white-space: pre-wrap;
                        color: var(--text-secondary);
                    }

                    /* Footer */
                    .footer {
                        margin-top: 2rem;
                        text-align: center;
                        font-size: 0.75rem;
                        color: var(--text-secondary);
                    }

                    /* Color indicators */
                    .text-green { color: var(--accent-green); }
                    .text-blue { color: var(--accent-blue); }
                    .text-purple { color: var(--accent-purple); }
                    .text-orange { color: var(--accent-orange); }
                    .text-red { color: var(--accent-red); }

                    /* Activity log */
                    .activity-log {
                        font-family: 'SF Mono', 'Fira Code', monospace;
                        font-size: 0.7rem;
                        background: rgba(0, 0, 0, 0.4);
                        border-radius: 0.75rem;
                        padding: 0.75rem;
                        max-height: 250px;
                        overflow-y: auto;
                        scroll-behavior: smooth;
                    }
                    .activity-entry {
                        display: flex;
                        gap: 1rem;
                        padding: 0.25rem 0;
                        border-bottom: 1px solid rgba(255,255,255,0.05);
                    }
                    .activity-entry:last-child { border-bottom: none; }
                    .activity-id { color: var(--accent-blue); min-width: 70px; }
                    .activity-filename { color: var(--text-primary); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 280px; }
                    .activity-status { min-width: 90px; font-weight: 600; }
                    .activity-status.compressed { color: var(--accent-green); }
                    .activity-status.skipped { color: var(--accent-orange); }
                    .activity-status.failed { color: var(--accent-red); }
                    .activity-size { color: var(--text-secondary); min-width: 120px; }
                    .activity-savings { color: var(--accent-purple); min-width: 60px; }
                    .activity-time { color: var(--text-secondary); }

                    /* Collapsible */
                    details.card { cursor: pointer; }
                    details.card summary { list-style: none; }
                    details.card summary::-webkit-details-marker { display: none; }
                    details.card summary::after { content: ' ▶'; font-size: 0.7em; }
                    details.card[open] summary::after { content: ' ▼'; }
                    details.card .log-content { margin-top: 1rem; }

                    /* Resource gauges (CPU, Memory) */
                    .resource-gauge {
                        display: flex;
                        flex-direction: column;
                        gap: 0.5rem;
                    }
                    .gauge-bar-bg {
                        height: 1.2rem;
                        background: rgba(255, 255, 255, 0.1);
                        border-radius: 9999px;
                        overflow: hidden;
                    }
                    .gauge-bar {
                        height: 100%;
                        border-radius: 9999px;
                        transition: width 0.5s ease-out;
                    }
                    .gauge-bar.cpu {
                        background: linear-gradient(90deg, var(--accent-blue), var(--accent-purple), var(--accent-red));
                        background-size: 300% 100%;
                    }
                    .gauge-bar.cpu.low { background-position: 100% 0; }
                    .gauge-bar.cpu.medium { background-position: 50% 0; }
                    .gauge-bar.cpu.high { background-position: 0% 0; }
                    .gauge-bar.memory {
                        background: linear-gradient(90deg, var(--accent-green), var(--accent-orange), var(--accent-red));
                        background-size: 300% 100%;
                    }
                    .gauge-bar.memory.low { background-position: 100% 0; }
                    .gauge-bar.memory.medium { background-position: 50% 0; }
                    .gauge-bar.memory.high { background-position: 0% 0; }
                    .gauge-text {
                        font-size: 0.75rem;
                        color: var(--text-secondary);
                        text-align: center;
                    }
                    .gauge-percent {
                        font-size: 1.5rem;
                        font-weight: 700;
                        text-align: center;
                        color: var(--accent-orange);
                    }
                    .text-secondary { color: var(--text-secondary); }
                </style>
            </head>
            <body>
                <div class="bg-animation"></div>
                <div class="container">
                    <!-- Header -->
                    <div class="header">
                        <h1>🗜️ Squish</h1>
                        <div class="badges">
                            <span id="modeBadge" class="badge badge-mode">MODE: NORMAL</span>
                            <span id="compressionBadge" class="badge badge-compression">AGGRESSIVE</span>
                            <span id="statusBadge" class="badge badge-status">RUNNING</span>
                            <span id="elapsed" class="elapsed">00:00</span>
                        </div>
                    </div>

                    <!-- Progress Section -->
                    <div class="progress-section">
                        <div class="card progress-card">
                            <div class="card-header">Overall Progress</div>
                            <div class="progress-header">
                                <div>
                                    <span id="progressPercent" class="progress-percent">0</span>
                                    <span class="card-value">%</span>
                                </div>
                                <div class="card-sub">
                                    <span id="updated">0</span> / <span id="totalRecords">0</span> records
                                </div>
                            </div>
                            <div class="progress-bar-container">
                                <div id="progressBar" class="progress-bar" style="width: 0%"></div>
                            </div>
                        </div>
                    </div>

                    <!-- DB Size Section -->
                    <div class="card" style="margin-bottom: 1.5rem;">
                        <div class="card-header">Database Size Reduction</div>
                        <div class="size-comparison">
                            <div class="size-item">
                                <div class="size-label">Initial</div>
                                <div id="initialDb" class="size-value">0 MB</div>
                            </div>
                            <div class="arrow">→</div>
                            <div class="size-item">
                                <div class="size-label">Projected</div>
                                <div id="projectedDb" class="size-value blue">0 MB</div>
                            </div>
                            <div class="arrow">→</div>
                            <div class="size-item">
                                <div class="size-label">Actual</div>
                                <div id="finalDb" class="size-value green">-</div>
                            </div>
                            <div class="size-item">
                                <div class="size-label">Savings</div>
                                <div id="savings" class="size-value purple">-</div>
                            </div>
                        </div>
                    </div>

                    <!-- Stats Grid -->
                    <div class="grid grid-4" style="margin-bottom: 1.5rem;">
                        <div class="card">
                            <div class="card-header">Processed</div>
                            <div id="read" class="card-value">0</div>
                            <div class="card-sub">PDFs: <span id="compressed">0</span> | Skipped: <span id="skipped">0</span></div>
                        </div>
                        <div class="card">
                            <div class="card-header">Compression</div>
                            <div class="card-value"><span id="savingsPercent" class="text-green">0</span>%</div>
                            <div class="card-sub">Ratio: <span id="ratio">1.00</span></div>
                        </div>
                        <div class="card">
                            <div class="card-header">Volume</div>
                            <div id="compressedSize" class="card-value">0 B</div>
                            <div class="card-sub">Original: <span id="originalSize">0 B</span></div>
                        </div>
                        <div class="card">
                            <div class="card-header">Health</div>
                            <div class="card-value"><span id="errors" class="text-red">0</span> errors</div>
                            <div class="card-sub">DLQ: <span id="dlq">0</span></div>
                        </div>
                    </div>

                    <!-- Throughput Grid -->
                    <div class="grid grid-3" style="margin-bottom: 1.5rem;">
                        <div class="card">
                            <div class="card-header">Records/sec</div>
                            <div id="recordsPerSec" class="card-value text-blue">0</div>
                        </div>
                        <div class="card">
                            <div class="card-header">MB/sec</div>
                            <div id="mbPerSec" class="card-value text-purple">0</div>
                        </div>
                        <div class="card">
                            <div class="card-header">Avg Time/PDF</div>
                            <div class="card-value"><span id="avgTime">0</span> ms</div>
                        </div>
                    </div>

                    <!-- System Resources Grid -->
                    <div class="grid grid-3" style="margin-bottom: 1.5rem;">
                        <div class="card">
                            <div class="card-header">CPU Usage</div>
                            <div class="resource-gauge">
                                <div class="gauge-bar-bg">
                                    <div id="cpuBar" class="gauge-bar cpu" style="width: 0%"></div>
                                </div>
                                <div class="gauge-text">
                                    <span id="cpuPercent" class="text-blue">0</span>% <span class="text-secondary">(<span id="cpuCores">0</span> cores)</span>
                                </div>
                            </div>
                        </div>
                        <div class="card">
                            <div class="card-header">JVM Memory</div>
                            <div class="resource-gauge">
                                <div class="gauge-bar-bg">
                                    <div id="memoryBar" class="gauge-bar memory" style="width: 0%"></div>
                                </div>
                                <div class="gauge-text">
                                    <span id="memoryUsed" class="text-orange">0 MB</span> / <span id="memoryMax">0 MB</span>
                                </div>
                                <div class="gauge-percent"><span id="memoryPercent">0</span>%</div>
                            </div>
                        </div>
                        <div class="card">
                            <div class="card-header">Active Threads</div>
                            <div id="activeThreads" class="card-value text-purple">0</div>
                        </div>
                    </div>

                    <!-- Activity Tail -->
                    <div class="log-section">
                        <div class="card">
                            <div class="card-header">Recent Activity (last 50)</div>
                            <div id="activityLog" class="activity-log"></div>
                        </div>
                    </div>

                    <!-- Collapsible Raw API Response -->
                    <div class="log-section">
                        <details class="card">
                            <summary class="card-header collapsible">Raw API Response</summary>
                            <div id="log" class="log-content">Loading...</div>
                        </details>
                    </div>

                    <!-- Footer -->
                    <div class="footer">
                        <div>Squish v2.0 | Auto-refresh 2s | API: <code>/api/status</code> | Built with Virtual Threads</div>
                        <div style="margin-top: 0.5rem; opacity: 0.7;">Designed & Engineered by <strong>Lucsartech Srl</strong></div>
                    </div>
                </div>

                <script>
                    // Helper functions to compute values from raw bytes
                    function formatSize(bytes) {
                        if (bytes === 0) return '0 B';
                        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
                        const k = 1024;
                        const i = Math.floor(Math.log(bytes) / Math.log(k));
                        const size = bytes / Math.pow(k, i);
                        return size.toFixed(i < 2 ? 0 : 1) + ' ' + units[i];
                    }
                    function toMb(bytes) { return bytes / 1024 / 1024; }
                    function formatElapsed(seconds) {
                        const h = Math.floor(seconds / 3600);
                        const m = Math.floor((seconds % 3600) / 60);
                        const s = seconds % 60;
                        return h > 0
                            ? `${h}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`
                            : `${m}:${String(s).padStart(2,'0')}`;
                    }

                    async function refresh() {
                        try {
                            const res = await fetch('/api/status?_=' + Date.now());
                            if (!res.ok) return;

                            const json = await res.json();
                            const d = json.data;

                            // Compute MB values from raw bytes (Gson doesn't serialize methods)
                            const originalMb = toMb(d.originalBytes);
                            const compressedMb = toMb(d.compressedBytes);
                            const initialDbMb = toMb(d.initialDbSizeBytes);
                            const finalDbMb = toMb(d.finalDbSizeBytes);
                            const projectedFinalMb = toMb(d.projectedFinalBytes);
                            const elapsedFormatted = formatElapsed(d.elapsedSeconds);

                            // Mode badge
                            const modeBadge = document.getElementById('modeBadge');
                            modeBadge.textContent = 'MODE: ' + json.mode;
                            modeBadge.classList.toggle('dry-run', json.mode.includes('DRY'));

                            // Compression mode badge
                            const compBadge = document.getElementById('compressionBadge');
                            compBadge.textContent = json.compressionMode;
                            compBadge.className = 'badge badge-compression ' + json.compressionMode.toLowerCase();

                            // Status badge
                            const statusBadge = document.getElementById('statusBadge');
                            statusBadge.textContent = d.completed ? 'COMPLETED' : 'RUNNING';
                            statusBadge.classList.toggle('completed', d.completed);

                            // Elapsed
                            document.getElementById('elapsed').textContent = elapsedFormatted;

                            // Progress
                            const progress = Math.min(100, Math.max(0, d.progressPercent));
                            document.getElementById('progressPercent').textContent = progress.toFixed(1);
                            document.getElementById('progressBar').style.width = progress + '%';
                            document.getElementById('updated').textContent = d.updated.toLocaleString();
                            document.getElementById('totalRecords').textContent = d.totalRecords.toLocaleString();

                            // DB sizes (human-readable)
                            document.getElementById('initialDb').textContent = formatSize(d.initialDbSizeBytes);
                            document.getElementById('projectedDb').textContent = formatSize(d.projectedFinalBytes);

                            // Current/Final DB size - always show (works in batch and watchdog mode)
                            const currentSize = d.completed ? d.finalDbSizeBytes : d.currentDbSizeBytes;
                            document.getElementById('finalDb').textContent = formatSize(currentSize);
                            const savedBytes = d.initialDbSizeBytes - currentSize;
                            const pct = d.initialDbSizeBytes > 0 ? (savedBytes / d.initialDbSizeBytes * 100) : 0;
                            document.getElementById('savings').textContent = formatSize(savedBytes) + ' (' + pct.toFixed(1) + '%)';

                            // Stats
                            document.getElementById('read').textContent = d.read.toLocaleString();
                            document.getElementById('compressed').textContent = d.compressed.toLocaleString();
                            document.getElementById('skipped').textContent = d.skipped.toLocaleString();
                            document.getElementById('savingsPercent').textContent = d.savingsPercent.toFixed(1);
                            document.getElementById('ratio').textContent = d.compressionRatio.toFixed(4);
                            document.getElementById('compressedSize').textContent = formatSize(d.compressedBytes);
                            document.getElementById('originalSize').textContent = formatSize(d.originalBytes);
                            document.getElementById('errors').textContent = d.errors;
                            document.getElementById('dlq').textContent = d.dlqSize;

                            // Throughput
                            document.getElementById('recordsPerSec').textContent = d.recordsPerSecond.toFixed(1);
                            document.getElementById('mbPerSec').textContent = d.mbPerSecond.toFixed(2);
                            document.getElementById('avgTime').textContent = d.avgProcessingTimeMs;

                            // System resources (CPU, Memory, Threads)
                            const sys = json.system;
                            if (sys) {
                                // CPU gauge
                                document.getElementById('cpuPercent').textContent = sys.cpuPercent.toFixed(0);
                                document.getElementById('cpuCores').textContent = sys.cpuCores;
                                const cpuBar = document.getElementById('cpuBar');
                                cpuBar.style.width = Math.min(100, sys.cpuPercent) + '%';
                                cpuBar.className = 'gauge-bar cpu ' + (sys.cpuPercent < 50 ? 'low' : sys.cpuPercent < 80 ? 'medium' : 'high');

                                // Memory gauge
                                document.getElementById('memoryUsed').textContent = formatSize(sys.memUsed);
                                document.getElementById('memoryMax').textContent = formatSize(sys.memMax);
                                document.getElementById('memoryPercent').textContent = sys.memPercent.toFixed(0);
                                const memBar = document.getElementById('memoryBar');
                                memBar.style.width = sys.memPercent + '%';
                                memBar.className = 'gauge-bar memory ' + (sys.memPercent < 50 ? 'low' : sys.memPercent < 80 ? 'medium' : 'high');

                                // Threads
                                document.getElementById('activeThreads').textContent = sys.activeThreads;
                            }

                            // Activity log
                            const activityLog = document.getElementById('activityLog');
                            if (json.activity && json.activity.length > 0) {
                                activityLog.innerHTML = json.activity.map(a => `
                                    <div class="activity-entry">
                                        <span class="activity-id">${a.id}</span>
                                        <span class="activity-filename" title="${a.filename || ''}">${a.filename || '-'}</span>
                                        <span class="activity-status ${a.status.toLowerCase()}">${a.status}</span>
                                        <span class="activity-size">${formatSize(a.originalSize)} → ${formatSize(a.compressedSize)}</span>
                                        <span class="activity-savings">${a.savingsPercent.toFixed(1)}%</span>
                                        <span class="activity-time">${a.timeMs}ms</span>
                                    </div>
                                `).join('');
                            } else {
                                activityLog.innerHTML = '<div style="color: var(--text-secondary); text-align: center; padding: 1rem;">Waiting for activity...</div>';
                            }

                            // Log (collapsed by default)
                            document.getElementById('log').textContent = JSON.stringify(json, null, 2);

                        } catch (e) {
                            console.error('Refresh error:', e);
                        }
                    }

                    refresh();
                    setInterval(refresh, 2000);
                </script>
            </body>
            </html>
            """;
    }
}
