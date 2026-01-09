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
- `application-prod.yml` - Production profile (reads from environment variables)

### Production Deployment

In production, all configuration is passed via **environment variables** for security. Credentials are NOT exposed in process listings (`ps aux`). See `dist/config/squish.env.template`.

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

  query:
    master-table: OTTICA              # Main table with PDF metadata
    detail-table: OTTICAI             # Detail table with PDF data
    tracking-table: SQUISH_PROCESSED  # Tracking table for processed records
    id-column: OTT_ID                 # Primary key column
    filename-column: OTT_NOME_FILE    # Filename column
    detail-id-column: OTTI_ID         # Detail table ID column
    data-column: OTTI_DATA            # BLOB column with PDF data
    # Generic WHERE filter (examples below)
    master-table-filter: "OTT_TIPO_DOC = '001030'"
    # master-table-filter: "OTT_TIPO_DOC IN ('001030','001031') AND OTT_STATUS = 'A'"
    # master-table-filter: "OTT_TIPO_DOC = '001030' AND CREATED_DATE > DATE '2024-01-01'"

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
    ssl-enabled: false           # Enable HTTPS
    keystore-path: /path/to/keystore.p12
    keystore-password: changeit
    keystore-type: PKCS12        # or JKS
    ssl-protocol: TLSv1.3

  watchdog:
    enabled: false
    poll-interval-seconds: 60

  report:
    enabled: true          # Enable/disable PDF reports
    directory: reports     # Output directory for reports

  email:
    enabled: false
    smtp-host: smtp.example.com
    smtp-port: 587               # 587=STARTTLS, 465=SSL
    smtp-user: user
    smtp-password: pass
    ssl: false                   # Direct SSL (port 465)
    starttls: true               # STARTTLS (port 587)
    ssl-protocols: TLSv1.2,TLSv1.3
    trust-all-certs: false
    connection-timeout: 10000
    read-timeout: 30000
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

## Database Setup

Before first run, create the tracking table:

```bash
sqlplus user/pass@db @dist/sql/create_tracking_table.sql
```

### Tracking Table (SQUISH_PROCESSED)

| Column | Description |
|--------|-------------|
| `OTT_ID` | Reference to source record (unique) |
| `ORIGINAL_SIZE` | Original file size in bytes |
| `COMPRESSED_SIZE` | Compressed size (NULL for skipped/error) |
| `STATUS` | `SUCCESS`, `SKIPPED`, or `ERROR` |
| `ERROR_MESSAGE` | Error/skip reason |
| `PROCESSED_DATE` | Processing timestamp |
| `HOSTNAME` | Server hostname |

### PDF Validation

Files are validated before compression using magic bytes check (`%PDF-`):
- `SUCCESS` - Valid PDF, compressed successfully
- `SKIPPED` - Not a PDF file (e.g., images, Word docs)
- `ERROR` - Compression failed (corrupt, encrypted, etc.)

All statuses are tracked in `SQUISH_PROCESSED` to avoid re-processing.

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
