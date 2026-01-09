# Squish - Installation Guide

## Prerequisites

- **Java 22** or higher (with Virtual Threads support)
- **Oracle Database** connectivity (JDBC)
- **2GB RAM** minimum (4GB recommended)

---

## Quick Installation (Linux/macOS)

```bash
# 1. Create installation directory
sudo mkdir -p /opt/squish
sudo chown $USER:$USER /opt/squish

# 2. Copy files
cp squish.jar /opt/squish/
cp -r bin/ /opt/squish/
cp -r config/ /opt/squish/
cp -r sql/ /opt/squish/
mkdir -p /opt/squish/logs

# 3. Create tracking table in Oracle
sqlplus user/pass@db @/opt/squish/sql/create_tracking_table.sql

# 4. Configure
cp /opt/squish/config/squish.env.template /opt/squish/config/squish.env
chmod 600 /opt/squish/config/squish.env
# Edit squish.env with your database credentials

# 5. Start
cd /opt/squish
./bin/squish.sh start

# 6. Check status
./bin/squish.sh status
# Dashboard: http://localhost:8080/
```

---

## Windows Installation

```batch
REM 1. Create installation directory
mkdir C:\squish
mkdir C:\squish\bin
mkdir C:\squish\config
mkdir C:\squish\logs

REM 2. Copy files
copy squish.jar C:\squish\
copy bin\squish.bat C:\squish\bin\
copy config\application.yml C:\squish\config\

REM 3. Configure environment variables
set DB_USER=your_user
set DB_PASSWORD=your_password
set SPRING_PROFILES_ACTIVE=prod

REM 4. Start
cd C:\squish
bin\squish.bat start
```

---

## Linux Systemd Service

```bash
# 1. Create service user
sudo useradd -r -s /bin/false squish

# 2. Install files
sudo mkdir -p /opt/squish/{logs,config}
sudo cp squish.jar /opt/squish/
sudo cp config/squish.env /opt/squish/config/
sudo chown -R squish:squish /opt/squish
sudo chmod 600 /opt/squish/config/squish.env

# 3. Install service
sudo cp service/squish.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable squish

# 4. Start service
sudo systemctl start squish

# 5. Check status
sudo systemctl status squish
journalctl -u squish -f
```

---

## Linux Legacy (SysV Init - RHEL/CentOS 6, older systems)

```bash
# 1. Create service user
sudo useradd -r -s /sbin/nologin squish

# 2. Install files
sudo mkdir -p /opt/squish/{logs,config}
sudo cp squish.jar /opt/squish/
sudo cp config/squish.env.template /opt/squish/config/squish.env
sudo chown -R squish:squish /opt/squish
sudo chmod 600 /opt/squish/config/squish.env

# 3. Install init script
sudo cp service/squish.init /etc/init.d/squish
sudo chmod +x /etc/init.d/squish

# 4. Enable service
# RHEL/CentOS:
sudo chkconfig --add squish
sudo chkconfig squish on

# Debian/Ubuntu (older):
sudo update-rc.d squish defaults

# 5. Configure (edit environment)
sudo vi /etc/sysconfig/squish   # RHEL/CentOS
sudo vi /etc/default/squish     # Debian/Ubuntu

# 6. Start service
sudo service squish start

# 7. Check status
sudo service squish status
tail -f /var/log/squish.log
```

### Environment File (/etc/sysconfig/squish)

```bash
JAVA_HOME=/usr/lib/jvm/java-22
JAVA_OPTS="-Xms512m -Xmx4g"
SPRING_PROFILE=prod
USER=squish
```

---

## Docker Installation

```bash
# 1. Build image
docker build -t squish:2.0 .

# 2. Run with environment variables
docker run -d \
  --name squish \
  -p 8080:8080 \
  -e DB_URL="jdbc:oracle:thin:@//dbserver:1521/SERVICE" \
  -e DB_USER="username" \
  -e DB_PASSWORD="password" \
  -v $(pwd)/logs:/app/logs \
  squish:2.0

# Or use docker-compose
docker-compose up -d
```

---

## Database Setup

Before running Squish, create the tracking table to avoid re-processing records:

### Create Tracking Table

```bash
# Using SQL*Plus
sqlplus user/pass@service @sql/create_tracking_table.sql

# Or copy/paste the DDL manually
```

The tracking table DDL is Oracle 11g+ compatible (uses sequence + trigger for auto-increment).

### Tracking Table Structure

| Column | Type | Description |
|--------|------|-------------|
| `ID` | NUMBER | Auto-increment primary key |
| `OTT_ID` | NUMBER | Reference to source record (unique) |
| `ORIGINAL_SIZE` | NUMBER | Original file size in bytes |
| `COMPRESSED_SIZE` | NUMBER | Compressed size (NULL for skipped/error) |
| `SAVINGS_PERCENT` | NUMBER(5,2) | Compression savings percentage |
| `STATUS` | VARCHAR2(20) | `SUCCESS`, `SKIPPED`, or `ERROR` |
| `ERROR_MESSAGE` | VARCHAR2(500) | Error/skip reason |
| `PROCESSED_DATE` | TIMESTAMP | Processing timestamp |
| `HOSTNAME` | VARCHAR2(100) | Server that processed the record |

### Processing Status Values

| Status | Description |
|--------|-------------|
| `SUCCESS` | PDF compressed successfully |
| `SKIPPED` | Not a PDF file (failed `%PDF-` magic bytes check) |
| `ERROR` | Compression failed (corrupt PDF, encrypted, etc.) |

### Statistics View

A convenience view is created for aggregate statistics:

```sql
SELECT * FROM SQUISH_STATS;
-- STATUS | RECORD_COUNT | ORIGINAL_MB | COMPRESSED_MB | AVG_SAVINGS_PCT | FIRST_PROCESSED | LAST_PROCESSED
```

---

## Configuration

### Security Note

All configuration parameters are passed to Squish via **environment variables** (not command-line arguments). This ensures that sensitive data like database credentials and passwords are not visible in:
- Process listings (`ps aux`)
- System logs
- Shell history

The `squish.env` file should have restricted permissions:
```bash
chmod 600 /opt/squish/config/squish.env
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| **Database** | | |
| `DB_URL` | Oracle JDBC URL | - |
| `DB_USER` | Database username | - |
| `DB_PASSWORD` | Database password | - |
| **Query Configuration** | | |
| `MASTER_TABLE` | Main table with PDF metadata | `OTTICA` |
| `DETAIL_TABLE` | Detail table with PDF BLOB | `OTTICAI` |
| `TRACKING_TABLE` | Table for tracking processed records | `SQUISH_PROCESSED` |
| `ID_COLUMN` | Primary key column name | `OTT_ID` |
| `FILENAME_COLUMN` | Filename column name | `OTT_NOME_FILE` |
| `DETAIL_ID_COLUMN` | Detail table join column | `OTTI_ID` |
| `DATA_COLUMN` | BLOB column with PDF data | `OTTI_DATA` |
| `MASTER_TABLE_FILTER` | WHERE clause filter (see examples below) | `OTT_TIPO_DOC = '001030'` |
| **Pipeline** | | |
| `COMPRESSION_MODE` | Compression level | `AGGRESSIVE` |
| `DRY_RUN` | Test mode (no DB writes) | `false` |
| `WORKER_THREADS` | Number of workers | `16` |
| `ID_FROM` | Starting record ID | `0` |
| `ID_TO` | Ending record ID (0=no limit) | `0` |
| **SMTP Email** | | |
| `EMAIL_ENABLED` | Enable email notifications | `false` |
| `SMTP_HOST` | SMTP server hostname | - |
| `SMTP_PORT` | SMTP port (587=STARTTLS, 465=SSL) | `587` |
| `SMTP_USER` | SMTP username | - |
| `SMTP_PASSWORD` | SMTP password | - |
| `SMTP_SSL` | Use direct SSL connection | `false` |
| `SMTP_STARTTLS` | Use STARTTLS upgrade | `true` |
| `SMTP_SSL_PROTOCOLS` | Allowed TLS versions | `TLSv1.2,TLSv1.3` |
| `SMTP_FROM` | Sender email address | - |
| `SMTP_TO` | Recipient email address | - |
| **HTTPS Dashboard** | | |
| `HTTP_PORT` | Dashboard port | `8080` |
| `HTTP_SSL_ENABLED` | Enable HTTPS | `false` |
| `HTTP_KEYSTORE_PATH` | Path to PKCS12 keystore | - |
| `HTTP_KEYSTORE_PASSWORD` | Keystore password | - |
| `HTTP_KEYSTORE_TYPE` | Keystore type | `PKCS12` |
| `HTTP_SSL_PROTOCOL` | TLS protocol version | `TLSv1.3` |
| **Watchdog** | | |
| `WATCHDOG_ENABLED` | Enable continuous monitoring | `false` |
| `WATCHDOG_INTERVAL` | Poll interval in seconds | `300` |
| **Report** | | |
| `REPORT_ENABLED` | Enable PDF report generation | `true` |
| `REPORT_DIRECTORY` | Output directory for reports | `reports` |
| **General** | | |
| `JAVA_HOME` | Path to Java 22+ installation | - |
| `JAVA_OPTS` | JVM options | `-Xms256m -Xmx2g` |
| `SPRING_PROFILES_ACTIVE` | Config profile | `prod` |

### Master Table Filter Examples

The `MASTER_TABLE_FILTER` variable accepts any valid SQL WHERE clause fragment. Complex filters with `=`, `AND`, quotes, and special characters are fully supported via environment variables.

```bash
# Single value filter
MASTER_TABLE_FILTER="OTT_TIPO_DOC = '001030'"

# Multiple values with IN
MASTER_TABLE_FILTER="OTT_TIPO_DOC IN ('001030','001031','001032')"

# Combined conditions
MASTER_TABLE_FILTER="OTT_TIPO_DOC = '001030' AND OTT_STATUS = 'A'"

# Date filter (Oracle DATE literal)
MASTER_TABLE_FILTER="OTT_TIPO_DOC = '001030' AND CREATED_DATE > DATE '2024-01-01'"

# Numeric date column (YYYYMMDD format)
MASTER_TABLE_FILTER="OTT_TIPO_DOC = '001030' AND OTT_DATA_INS BETWEEN 20120101 AND 20121231"

# Complex filter with ROWNUM limit
MASTER_TABLE_FILTER="OTT_TIPO_DOC IN ('001030','001031') AND OTT_STATUS = 'A' AND ROWNUM <= 10000"
```

> **Note**: For DATE columns, use Oracle date literals (`DATE '2024-01-01'`) or `TO_DATE()` function. For NUMBER columns storing dates as YYYYMMDD, use numeric values directly.

### Compression Modes

| Mode | Quality | Use Case |
|------|---------|----------|
| `LOSSLESS` | 100% | Legal, archival documents |
| `MEDIUM` | 80% | General office documents |
| `AGGRESSIVE` | 60% | Maximum storage savings |

### JVM Tuning

```bash
# Production settings (4GB heap, ZGC)
JAVA_OPTS="-Xms1g -Xmx4g -XX:+UseZGC -XX:+ZGenerational"

# Low memory (1GB heap)
JAVA_OPTS="-Xms256m -Xmx1g -XX:+UseSerialGC"
```

---

## HTTPS Configuration

To enable HTTPS for the dashboard:

### 1. Generate a Self-Signed Certificate

```bash
keytool -genkeypair -alias squish -keyalg RSA -keysize 2048 \
  -storetype PKCS12 -keystore /opt/squish/config/squish.p12 \
  -validity 3650 -storepass changeit \
  -dname "CN=squish, OU=IT, O=Company, L=City, S=State, C=IT"
```

### 2. Configure Environment

```bash
# In squish.env
HTTP_SSL_ENABLED=true
HTTP_KEYSTORE_PATH=/opt/squish/config/squish.p12
HTTP_KEYSTORE_PASSWORD=changeit
HTTP_KEYSTORE_TYPE=PKCS12
HTTP_SSL_PROTOCOL=TLSv1.3
```

### 3. Access Dashboard

```
https://localhost:8080/
```

> **Note**: For production, use a certificate from a trusted CA.

---

## SMTP Email Configuration

### STARTTLS (Port 587 - Recommended)

```bash
# In squish.env
SMTP_HOST=smtp.company.com
SMTP_PORT=587
SMTP_USER=user@company.com
SMTP_PASSWORD=secret
SMTP_STARTTLS=true
SMTP_SSL=false
SMTP_SSL_PROTOCOLS=TLSv1.2,TLSv1.3
SMTP_FROM=squish@company.com
SMTP_TO=it-operations@company.com
```

### Direct SSL (Port 465)

```bash
# In squish.env
SMTP_HOST=smtp.company.com
SMTP_PORT=465
SMTP_USER=user@company.com
SMTP_PASSWORD=secret
SMTP_STARTTLS=false
SMTP_SSL=true
SMTP_SSL_PROTOCOLS=TLSv1.2,TLSv1.3
```

### Common SMTP Servers

| Provider | Host | Port | Auth |
|----------|------|------|------|
| Office 365 | smtp.office365.com | 587 | STARTTLS |
| Gmail | smtp.gmail.com | 587 | STARTTLS |
| Amazon SES | email-smtp.region.amazonaws.com | 587 | STARTTLS |
| On-premise | smtp.company.local | 25/587 | Optional |

---

## Monitoring

- **Dashboard**: http://localhost:8080/ (or https:// if SSL enabled)
- **API Status**: http://localhost:8080/api/status
- **Health Check**: http://localhost:8080/api/health

---

## Troubleshooting

### Connection Pool Timeout
```
Increase max-pool-size in configuration
compressor.database.max-pool-size: 20
```

### Out of Memory
```
Increase JVM heap size
JAVA_OPTS="-Xmx4g"
```

### Slow Performance
```
Increase worker threads
compressor.pipeline.worker-threads: 16
```

---

## Support

**Designed & Engineered by Lucsartech Srl**

- GitHub: https://github.com/gsimonini70/squish
- Issues: https://github.com/gsimonini70/squish/issues
