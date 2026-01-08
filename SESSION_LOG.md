# Squish - Session Log (2026-01-07)

## Attività completate

### 1. Script di installazione/disinstallazione
Creati e pushati su GitHub:
- `dist/bin/install.sh` - Linux/macOS
- `dist/bin/uninstall.sh` - Linux/macOS
- `dist/bin/install.bat` - Windows
- `dist/bin/uninstall.bat` - Windows

### 2. Test dry-run con profilo prod
Eseguito con successo collegandosi a:
- **Database**: `fraviapp006.eur.mccormick.com:1521/FDS1`
- **User**: `fides`

**Risultati:**
- 71 record elaborati
- 73,40 MB → 7,96 MB (89,2% risparmio)
- Modalità: AGGRESSIVE + WATCHDOG

### 3. Repository GitHub
https://github.com/gsimonini70/squish.git

## Comandi utili

```bash
# Build
mvn clean package -DskipTests

# Run dry-run prod
java -jar target/pdf-compressor-modern-2.0.0.jar \
  --spring.profiles.active=prod \
  --compressor.dry-run=true \
  --compressor.database.jdbc-url="jdbc:oracle:thin:@//fraviapp006.eur.mccormick.com:1521/FDS1" \
  --compressor.database.username="fides" \
  --compressor.database.password="s1nf02012"

# Build distribution
./build-dist.sh
```

## File distribuzione
- `target/squish-2.0.0.tar.gz`
- `target/squish-2.0.0.zip`
