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
mkdir -p /opt/squish/logs

# 3. Configure
cp /opt/squish/config/squish.env.template /opt/squish/config/squish.env
chmod 600 /opt/squish/config/squish.env
# Edit squish.env with your database credentials

# 4. Start
cd /opt/squish
./bin/squish.sh start

# 5. Check status
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

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `DB_URL` | Oracle JDBC URL | - |
| `DB_USER` | Database username | - |
| `DB_PASSWORD` | Database password | - |
| `JAVA_OPTS` | JVM options | `-Xms256m -Xmx2g` |
| `SPRING_PROFILES_ACTIVE` | Config profile | `prod` |

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

## Monitoring

- **Dashboard**: http://localhost:8080/
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
