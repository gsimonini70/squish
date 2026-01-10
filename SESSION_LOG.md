# Squish - Session Log

## Session 2026-01-10

### Composite Primary Key Support (OTTI_ID, OTTI_CTR)

**Problema**: La tabella OTTICAI ha una chiave primaria composita (OTTI_ID, OTTI_CTR), ma le UPDATE statement usavano solo OTTI_ID, causando aggiornamenti multipli errati.

**Errore riscontrato**: `ORA-00001: unique constraint violated` sulla tabella SQUISH_PROCESSED.

**Soluzione implementata**:

1. **PdfCompressorProperties.java**
   - Aggiunto `detailCtrColumn` con default `OTTI_CTR`
   - Getter/setter per la nuova proprietà

2. **PdfTask.java**
   - Aggiunto campo `ctr` al record `Data`
   - Signature: `Data(long id, long ctr, String filename, byte[] pdf)`

3. **CompressionResult.java**
   - Aggiunto `ctr` a tutti i record: `Success`, `Failure`, `Skipped`
   - Metodo `ctr()` nell'interfaccia sealed

4. **PdfCompressor.java**
   - Aggiornata signature: `compress(long id, long ctr, String filename, byte[] input)`
   - `ctr` propagato in tutti i result

5. **CompressionPipeline.java**
   - SELECT include `OTTI_CTR`
   - UPDATE usa `WHERE OTTI_ID = ? AND OTTI_CTR = ?`
   - Poison pill include `ctr = 0`

6. **WatchdogService.java**
   - SELECT include `OTTI_CTR`
   - UPDATE usa chiave composita
   - `processRecord()` aggiornato con `ctr`

7. **Configurazione**
   - `application.yml`: aggiunto `detail-ctr-column: OTTI_CTR`
   - `application-prod.yml`: aggiunto `${DETAIL_CTR_COLUMN:OTTI_CTR}`
   - `squish.env.template`: documentato `DETAIL_CTR_COLUMN`
   - `squish.init`: runtime YAML include `detail-ctr-column`

8. **Documentazione**
   - `README.md`: aggiornata configurazione query
   - `INSTALL.md`: aggiunto `DETAIL_CTR_COLUMN` alle variabili ambiente
   - `CLAUDE.md`: aggiornata documentazione

**Commit**: `29944e2` - Add composite primary key support for OTTICAI (OTTI_ID, OTTI_CTR)

**Package**: `target/squish-2.0.0.tar.gz` (19 MB)

---

## Session 2026-01-07

### Attività completate

#### 1. Script di installazione/disinstallazione
Creati e pushati su GitHub:
- `dist/bin/install.sh` - Linux/macOS
- `dist/bin/uninstall.sh` - Linux/macOS
- `dist/bin/install.bat` - Windows
- `dist/bin/uninstall.bat` - Windows

#### 2. Test dry-run con profilo prod
Eseguito con successo collegandosi a:
- **Database**: `fraviapp006.eur.mccormick.com:1521/FDS1`
- **User**: `fides`

**Risultati:**
- 71 record elaborati
- 73,40 MB -> 7,96 MB (89,2% risparmio)
- Modalità: AGGRESSIVE + WATCHDOG

#### 3. Repository GitHub
https://github.com/gsimonini70/squish.git

---

## Comandi utili

```bash
# Build
mvn clean package -DskipTests

# Run dry-run prod
java -jar target/pdf-compressor-modern-2.0.0.jar \
  --spring.profiles.active=prod \
  --compressor.dry-run=true

# Build distribution
cd target && tar -czvf squish-2.0.0.tar.gz squish-2.0.0/
```

## Struttura Package

```
squish-2.0.0/
├── squish.jar              # JAR eseguibile
├── VERSION
├── bin/
│   ├── install.sh / .bat
│   ├── uninstall.sh / .bat
│   └── squish.sh / .bat
├── config/
│   ├── application.yml
│   ├── squish.env.template
│   └── squish.env.bat.template
├── service/
│   ├── squish.init         # SysV init
│   └── squish.service      # systemd
├── sql/
│   └── create_tracking_table.sql
├── docs/
│   └── INSTALL.md
├── logs/
└── reports/
```

## Commits recenti

| Commit | Descrizione |
|--------|-------------|
| `29944e2` | Add composite primary key support for OTTICAI (OTTI_ID, OTTI_CTR) |
| `19a7bb8` | Dashboard actual size, compact report, and MERGE upsert fix |
| `69588b0` | Secure init script: credentials not visible in ps aux |
| `666d6d3` | Security and configuration improvements |
