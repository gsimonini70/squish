# CLAUDE.md

## Project Overview

Modern PDF compression pipeline (v2.0) that reads PDFs from Oracle Database, compresses them with configurable quality levels, and writes compressed versions back. Uses **Java 22** with **Virtual Threads** (Project Loom) for high-performance I/O. Built with **Spring Boot 3.2** for configuration management.

## Build & Run

```bash
# Build (creates executable Spring Boot JAR)
mvn clean package -DskipTests

# Run with default profile
java -jar target/pdf-compressor-modern-2.0.0.jar

# Run with specific profile
java -jar target/pdf-compressor-modern-2.0.0.jar --spring.profiles.active=test
java -jar target/pdf-compressor-modern-2.0.0.jar --spring.profiles.active=prod

# Override specific properties
java -jar target/pdf-compressor-modern-2.0.0.jar \
  --compressor.mode=AGGRESSIVE \
  --compressor.dry-run=true
```

## Configuration

All configuration is done via YAML files in `src/main/resources/`:

- `application.yml` - Base configuration
- `application-dev.yml` - Development profile
- `application-test.yml` - Test profile
- `application-prod.yml` - Production profile

### Key Configuration Options

```yaml
compressor:
  mode: AGGRESSIVE  # LOSSLESS, MEDIUM, AGGRESSIVE
  dry-run: false

  database:
    jdbc-url: jdbc:oracle:thin:@//host:1521/service
    username: user
    password: pass
    max-pool-size: 12

  pipeline:
    worker-threads: 8
    id-from: 0
    id-to: 0  # 0 = no limit
    fetch-size: 200
    batch-size: 200
    queue-capacity: 500
    throttle-millis: 0

  http:
    port: 8080

  watchdog:
    enabled: false
    poll-interval-seconds: 60

  email:
    enabled: false
    smtp-host: smtp.example.com
    smtp-port: 587
    from: noreply@example.com
    to:
      - admin@example.com
```

## Architecture

```
Producer (Virtual Thread - DB Read)
    ↓ BlockingQueue<PdfTask>
Worker Pool (Virtual Threads + Semaphore)
    ↓ BlockingQueue<CompressionResult>
Writer Pool (Virtual Threads + Semaphore)
```

## Key Packages

- `config/` - PdfCompressorProperties (Spring Boot), BeanConfiguration
- `compression/` - PdfCompressor, CompressionResult (sealed interface)
- `pipeline/` - CompressionPipeline, WatchdogService, ProgressTracker
- `http/` - MonitorServer (Glassmorphism dashboard)
- `report/` - ReportGenerator (PDF reports)
- `email/` - EmailService (SMTP notifications)

## Operating Modes

### Batch Mode (default)
Processes records once from `id-from` to `id-to`, then exits.

### Watchdog Mode
Set `compressor.watchdog.enabled=true` to enable continuous monitoring. Polls database for new records at configured interval.

## Monitoring

- Dashboard: `http://localhost:8080/`
- JSON API: `http://localhost:8080/api/status`
- Health: `http://localhost:8080/api/health`

## Key Dependencies

- **Spring Boot 3.2** - Application framework
- **iText 8** - PDF manipulation
- **TwelveMonkeys ImageIO** - Image encoding
- **HikariCP** - Connection pooling
- **SLF4J + Logback** - Structured logging
- **Gson** - JSON serialization
- **Jakarta Mail** - Email notifications

## Requirements

- Java 22+
- Maven 3.9+
- Oracle Database connectivity
