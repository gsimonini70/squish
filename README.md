# 🗜️ Squish - PDF Compression Engine

[![Java](https://img.shields.io/badge/Java-22+-orange.svg)](https://openjdk.org/projects/jdk/22/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Prometheus](https://img.shields.io/badge/Prometheus-Metrics-red.svg)](https://prometheus.io/)
[![License](https://img.shields.io/badge/License-Proprietary-blue.svg)]()

**Squish** is a high-performance PDF compression pipeline designed for enterprise workloads. Built with Java 22 Virtual Threads and Spring Boot 3.2, it efficiently processes large volumes of PDFs stored in Oracle Database.

> **Designed & Engineered by [Lucsartech Srl](https://lucsartech.com)**

---

## ✨ Features

- **High Performance** - Virtual Threads (Project Loom) for massive I/O concurrency
- **Configurable Compression** - Three modes: LOSSLESS, MEDIUM, AGGRESSIVE
- **Real-time Dashboard** - Beautiful glassmorphism UI with live metrics (HTTP/HTTPS)
- **System Monitoring** - CPU, Memory, Thread usage gauges
- **Activity Tracking** - Real-time log of processed PDFs with filenames
- **Batch & Watchdog Modes** - One-time processing or continuous monitoring
- **Email Notifications** - Automatic reports with secure SMTP (STARTTLS/SSL)
- **Dry-Run Mode** - Test without modifying database
- **PDF Validation** - Automatically skips non-PDF attachments (magic bytes check)
- **Processing Tracking** - Tracks all processed records to avoid re-processing
- **Flexible Query Filter** - Generic WHERE clause for complex record selection
- **Configurable Schema** - Adaptable table/column names for any schema

---

## 📊 Dashboard

The Squish dashboard provides real-time visibility into compression operations:

- **Progress tracking** with percentage and record count
- **Database size reduction** - Initial → Projected → Final
- **Compression statistics** - Ratio, savings percentage
- **Throughput metrics** - Records/sec, MB/sec
- **System resources** - CPU, Memory, Active Threads gauges
- **Activity log** - Last 50 processed files with status

Access the dashboard at: `http://localhost:8080/`

---

## 🚀 Quick Start

### Prerequisites

- Java 22 or higher
- Maven 3.9+
- Oracle Database connectivity

### Build

```bash
mvn clean package -DskipTests
```

### Run

```bash
# Default profile
java -jar target/squish-3.0.0.jar

# With specific profile
java -jar target/squish-3.0.0.jar --spring.profiles.active=prod

# Dry-run mode (no database writes)
java -jar target/squish-3.0.0.jar --squish.dry-run=true
```

---

## ⚙️ Configuration

Configuration is managed via YAML files in `src/main/resources/`:

| File | Description |
|------|-------------|
| `application.yml` | Base configuration |
| `application-dev.yml` | Development profile |
| `application-test.yml` | Test profile |
| `application-prod.yml` | Production profile |

### Configuration Options

```yaml
squish:
  # Compression mode: LOSSLESS, MEDIUM, AGGRESSIVE
  mode: AGGRESSIVE

  # Dry-run mode (no database writes)
  dry-run: false

  database:
    jdbc-url: jdbc:oracle:thin:@//host:1521/service
    username: user
    password: pass
    max-pool-size: 12

  # Query Configuration - table/column mapping
  query:
    master-table: OTTICA              # Main table with PDF metadata
    detail-table: OTTICAI             # Detail table with PDF BLOB data
    tracking-table: SQUISH_PROCESSED  # Tracking table (created by DDL)
    id-column: OTT_ID                 # Primary key column
    filename-column: OTT_NOME_FILE    # Filename column
    detail-id-column: OTTI_ID         # Detail table ID column (composite PK part 1)
    detail-ctr-column: OTTI_CTR       # Detail table CTR column (composite PK part 2)
    data-column: OTTI_DATA            # BLOB column with PDF data
    # Generic WHERE filter - supports any valid SQL condition
    master-table-filter: "OTT_TIPO_DOC = '001030'"
    # Examples:
    # master-table-filter: "OTT_TIPO_DOC IN ('001030','001031')"
    # master-table-filter: "OTT_TIPO_DOC = '001030' AND OTT_STATUS = 'A'"
    # master-table-filter: "CREATED_DATE > DATE '2024-01-01'"

  pipeline:
    worker-threads: 8      # Number of compression workers
    id-from: 0             # Starting record ID
    id-to: 0               # Ending record ID (0 = no limit)
    fetch-size: 200        # DB fetch batch size
    batch-size: 200        # Write batch size
    queue-capacity: 500    # Internal queue size
    throttle-millis: 0     # Throttle between records (ms)

  # HTTP/HTTPS Dashboard
  http:
    port: 8080
    ssl-enabled: false           # Enable HTTPS
    keystore-path: /path/to/keystore.p12
    keystore-password: changeit
    keystore-type: PKCS12
    ssl-protocol: TLSv1.3

  watchdog:
    enabled: false         # Enable continuous monitoring
    poll-interval-seconds: 60

  # Report generation
  report:
    enabled: true          # Enable/disable PDF reports
    directory: reports     # Output directory (relative or absolute)

  # Email notifications with secure protocols
  email:
    enabled: false
    smtp-host: smtp.example.com
    smtp-port: 587               # 587=STARTTLS, 465=SSL
    smtp-user: user
    smtp-password: pass
    ssl: false                   # Direct SSL (port 465)
    starttls: true               # STARTTLS (port 587)
    ssl-protocols: TLSv1.2,TLSv1.3
    trust-all-certs: false       # Only for testing
    connection-timeout: 10000
    read-timeout: 30000
    from: noreply@example.com
    to:
      - admin@example.com
```

---

## 🏗️ Architecture

Squish uses a multi-stage pipeline architecture optimized for I/O-bound operations:

```
┌─────────────────────────────────────────────────────────────┐
│                      SQUISH PIPELINE                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌──────────────┐                                          │
│  │   Producer   │  Virtual Thread - Reads from Oracle DB   │
│  │  (DB Read)   │                                          │
│  └──────┬───────┘                                          │
│         │                                                   │
│         ▼ BlockingQueue<PdfTask>                           │
│                                                             │
│  ┌──────────────┐                                          │
│  │ Worker Pool  │  Virtual Threads + Semaphore             │
│  │ (Compress)   │  PDF validation + iText compression      │
│  └──────┬───────┘                                          │
│         │                                                   │
│         ▼ BlockingQueue<CompressionResult>                 │
│                                                             │
│  ┌──────────────┐                                          │
│  │ Writer Pool  │  Virtual Threads + Batch commits         │
│  │  (DB Write)  │                                          │
│  └──────────────┘                                          │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Key Components

| Package | Description |
|---------|-------------|
| `config/` | Spring Boot configuration properties |
| `compression/` | PDF compression engine (iText 8) |
| `pipeline/` | Pipeline orchestration, progress tracking |
| `http/` | Dashboard and REST API |
| `report/` | PDF report generation |
| `email/` | SMTP email service |

---

## 📈 Compression Modes

| Mode | Image Scaling | JPEG Quality | Best For |
|------|---------------|--------------|----------|
| **LOSSLESS** | 100% | 100% | Archival, legal documents |
| **MEDIUM** | 85% | 80% | General office documents |
| **AGGRESSIVE** | 70% | 60% | Maximum storage savings |

---

## 🗃️ Database Setup

Before first run, create the tracking table to avoid re-processing records:

```sql
-- Run the DDL script (Oracle 11g+ compatible)
@sql/create_tracking_table.sql
```

The tracking table stores processing history:

| Column | Description |
|--------|-------------|
| `OTT_ID` | Reference to source record |
| `ORIGINAL_SIZE` | Original file size in bytes |
| `COMPRESSED_SIZE` | Compressed size (NULL for skipped/error) |
| `SAVINGS_PERCENT` | Compression savings percentage |
| `STATUS` | `SUCCESS`, `SKIPPED`, or `ERROR` |
| `ERROR_MESSAGE` | Error/skip reason |
| `PROCESSED_DATE` | Processing timestamp |
| `HOSTNAME` | Processing server hostname |

### Processing Status

| Status | Description |
|--------|-------------|
| `SUCCESS` | PDF compressed successfully |
| `SKIPPED` | Not a PDF file (failed magic bytes check `%PDF-`) |
| `ERROR` | Compression failed (corrupt PDF, etc.) |

### Statistics View

```sql
-- Check compression statistics by status
SELECT * FROM SQUISH_STATS;
```

---

## 🔌 API Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /` | Dashboard UI |
| `GET /api/status` | JSON status with all metrics |
| `GET /api/health` | Health check endpoint |
| `GET /metrics` | Prometheus metrics (text format) |

---

## 📊 Prometheus Monitoring

Squish 3.0 exports metrics in Prometheus format at `/metrics` endpoint.

### Available Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `squish_records_read_total` | Counter | Total records read from database |
| `squish_records_compressed_total` | Counter | Total PDFs successfully compressed |
| `squish_records_skipped_total` | Counter | Total records skipped (non-PDF) |
| `squish_records_failed_total` | Counter | Total compression failures |
| `squish_bytes_original_total` | Counter | Total original bytes processed |
| `squish_bytes_compressed_total` | Counter | Total compressed bytes produced |
| `squish_compression_ratio` | Gauge | Current compression ratio |
| `squish_savings_percent` | Gauge | Current savings percentage |
| `squish_queue_size` | Gauge | Compression queue size |
| `squish_active_workers` | Gauge | Active compression workers |
| `squish_watchdog_cycle` | Gauge | Current watchdog cycle |
| `squish_compression_duration` | Timer | Compression time histogram |
| `squish_jvm_cpu_usage` | Gauge | JVM CPU usage % |
| `squish_jvm_memory_used_bytes` | Gauge | JVM heap memory used |
| `squish_jvm_threads_active` | Gauge | Active JVM threads |

### Prometheus Configuration

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'squish'
    scrape_interval: 15s
    static_configs:
      - targets: ['squish-server:8080']
```

### Grafana Dashboard

Import the Squish dashboard or create custom panels:

```promql
# Compression rate
rate(squish_records_compressed_total[5m])

# Savings percentage
squish_savings_percent

# Throughput MB/s
rate(squish_bytes_original_total[1m]) / 1024 / 1024
```

### Example API Response

```json
{
  "data": {
    "read": 1000,
    "compressed": 950,
    "skipped": 30,
    "updated": 950,
    "errors": 20,
    "savingsPercent": 85.5,
    "progressPercent": 100.0
  },
  "mode": "DRY-RUN",
  "system": {
    "cpuPercent": 45.2,
    "cpuCores": 10,
    "memPercent": 35.0,
    "activeThreads": 27
  },
  "activity": [...]
}
```

---

## 🛠️ Operating Modes

### Batch Mode (Default)

Processes all records from `id-from` to `id-to` and exits:

```bash
java -jar squish.jar --squish.pipeline.id-from=1 --squish.pipeline.id-to=10000
```

### Watchdog Mode

Continuously monitors for new records:

```bash
java -jar squish.jar --squish.watchdog.enabled=true
```

---

## 🔒 Security

### HTTPS Dashboard

Generate a self-signed certificate:

```bash
keytool -genkeypair -alias squish -keyalg RSA -keysize 2048 \
  -storetype PKCS12 -keystore squish.p12 -validity 3650
```

Enable in configuration:

```yaml
squish:
  http:
    ssl-enabled: true
    keystore-path: /path/to/squish.p12
    keystore-password: changeit
    ssl-protocol: TLSv1.3
```

### Secure Email (SMTP)

Supports both STARTTLS (port 587) and direct SSL (port 465):

```yaml
squish:
  email:
    smtp-host: smtp.office365.com
    smtp-port: 587
    starttls: true
    ssl-protocols: TLSv1.2,TLSv1.3
```

---

## 📦 Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Spring Boot | 3.2.x | Application framework |
| iText | 8.x | PDF manipulation |
| HikariCP | 5.x | Connection pooling |
| Micrometer | 1.12.x | Prometheus metrics |
| TwelveMonkeys | 3.x | Image I/O support |
| Gson | 2.x | JSON serialization |
| Jakarta Mail | 2.x | Email notifications |
| Oracle JDBC | 23.x | Database connectivity |

---

## 📝 License

Proprietary software. All rights reserved.

**© 2024 Lucsartech Srl**

---

## 🤝 Support

For support and inquiries, contact [Lucsartech Srl](https://lucsartech.com).
