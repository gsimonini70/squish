# Squish - Session Log

## Session 2026-07-14

Sessione lunga, organizzata in agent paralleli con proprietà esclusiva dei file.
Tre blocchi: bug noti, kit di installazione, indagine su Java 21.
Suite passata da 105 a **143 test**, tutti verdi. Niente è stato committato.

### 1. Bug di robustezza (tutti dimostrati, non ipotizzati)

| Difetto | Perché era grave | Fix |
|---------|------------------|-----|
| `WatchdogService.runCycle()` catturava `Exception` ma non `Error` | Per contratto JDK, un task di `scheduleAtFixedRate` che lancia viene **cancellato per sempre**. Un OOM rendeva il servizio uno zombie: dashboard viva, zero record processati, nessun errore visibile. | Cattura `Throwable`. `Exception` → log, il ciclo dopo riparte. `Error` → log FATAL, spegne lo scheduler, `System.exit(1)` così il supervisor riavvia. |
| Nessuna backpressure nel watchdog | Il permesso del semaforo si acquisiva *dentro* il task async, quindi non frenava il produttore: tutti i BLOB del ciclo finivano in heap insieme. | Permesso acquisito **prima** di leggere il BLOB. Bound = `max(2, worker-threads * 2)`, derivato dalla config esistente. |
| `CompressionPipeline`: writer morto → hang infinito | Il `catch (Exception)` in `runWriter()` *era* il bug: un writer che moriva tornava zitto e smetteva di drenare la coda. I worker restavano bloccati su `put()` per sempre. | Seam `Handoff<T>` con `offer(timeout)` + conteggio consumatori vivi. La run fallisce rumorosamente invece di appendersi. Trovato anche un secondo hang: il producer, se lanciava, saltava le poison pill. |
| `ReportGenerator.generate()` restituiva il path anche dopo un'eccezione | `EmailService` allegava e **spediva al cliente un PDF troncato**. | Restituisce `null` e cancella l'artefatto. Spostato anche il `log.info` fuori dal try: iText finalizza il PDF nel `close()`, quindi un errore in chiusura loggava "Report generated" e poi entrava nel catch. |
| `ProgressTracker.failedRecords`: `CopyOnWriteArrayList` illimitata | Copia l'intero array a ogni `add` → O(n²). Una run con molti fallimenti si mangiava la heap da sola. | `ConcurrentLinkedQueue` + cap a 1000. Il **totale resta esatto** anche quando la lista è troncata, e la troncatura è dichiarata nel report e nel summary. |
| CI verde con i test rossi | `continue-on-error: true` su `mvn test` disarmava ogni altra rete di sicurezza. | Rimosso. Unificato in `mvn clean verify`: i test ora fanno da gate all'upload del jar (prima l'artifact si pubblicava comunque). |
| Version drift (pom 3.0.0, tutto il resto 2.0) | Il banner diceva `v2.0` a un cliente che eseguiva la 3.0.0. | `pom.xml` unica fonte di verità. `build-dist.sh` la deriva (**saltando il blocco `<parent>`**, dove il primo `<version>` è quello di Spring Boot). Il banner la legge dal manifest a runtime. |

**Trappola scoperta sul banner**: `getPackage().getImplementationVersion()` restituisce `null` sotto il classloader
annidato di Spring Boot (le classi stanno in `BOOT-INF/classes`). Il fallback legge il `MANIFEST.MF` identificando
*il nostro* jar dall'attributo `Start-Class`, non per ordine di enumerazione — sull'enumerazione ci sono anche i
manifest di spring-boot e spring-context.

### 2. Kit di installazione

**Bug catastrofico trovato e corretto**: Spring Boot carica `./config/application.yml` come location di default,
e un `application.yml` **esterno batte un `application-prod.yml` impacchettato nel jar**. Il file che spedivamo
conteneva valori literal (`jdbc-url: ...@//localhost:1521/ORCLPDB1`, `worker-threads: 8`), quindi
**il `DB_URL` del cliente veniva silenziosamente ignorato e l'app puntava a localhost**.
Il kit, così com'era, non poteva funzionare contro un database reale.
Verificato sul jar reale: col file vecchio vince `FROM-EXTERNAL-YAML`, con quello riscritto vince `FROM-ENV-VAR`.
Ora ogni voce è un placeholder `${ENV_VAR:default}`.
→ **Se sostituisci un placeholder con un literal, quel literal vince su `squish.env` e blocca il valore.**

Altri difetti chiusi:
- Il profilo `prod` **non aveva alcun blocco `squish.security.*`**: la password della dashboard era casuale a ogni riavvio e le API di scrittura inutilizzabili. Aggiunte `SECURITY_ENABLED`, `SECURITY_USERNAME`, `SECURITY_PASSWORD`, `CORS_ALLOWED_ORIGINS`, `ACTIVE_PROFILE`. Nessuna password di default: un default in un kit diventerebbe la password di *tutte* le installazioni.
- `ExecStart=${JAVA_HOME}/bin/java` nella unit systemd **non ha mai funzionato**: systemd non espande variabili nel primo token, la unit non si caricava nemmeno.
- `EnvironmentFile=` non riusciva a leggere `squish.env` (systemd non capisce `export` né i commenti in coda) e scartava in silenzio ogni variabile. Rimosso.
- I report PDF erano **non scrivibili** sotto `ProtectSystem=strict`: `ReadWritePaths` elencava solo `logs/`. Aggiunto `reports/`. Falliva solo in produzione.
- `install.sh` usava `mkdir -p dir/{bin,config,...}`: sotto `dash` (che è `/bin/sh` su Debian/Ubuntu) la brace expansion non esiste → creava una directory chiamata letteralmente `{bin,config,logs,service}`.
- `squish.bat`: `start /b "%JAVA_CMD%"` trattava il path di Java come **titolo della finestra** e tentava di eseguire `%JAVA_OPTS%` come programma.
- `build-dist.sh` costruiva con `-DskipTests`: il bundle spedito al cliente veniva da un albero non testato. Ora i test sono il gate.
- `create_tracking_table.sql` non era idempotente: una riesecuzione abortiva a metà lasciando lo schema monco.
- Il `docker-compose.yml` non passava nessuna variabile di sicurezza e non montava `reports/` (i report morivano col container).
- **SysV non fa respawn** e non è risolvibile senza inventare un supervisor. Dichiarato apertamente nello script: systemd è il percorso supportato. Aggiunta l'azione `check` (hook per cron) e `status` con codici LSB corretti (0 running / 1 **crashed** / 3 stopped).

**Nuovi file**: `bin/squish-run.sh` (unico launcher: legge `squish.env` con `set -a`, valida, fa `exec` del JVM
così il supervisor vede PID ed exit code veri) e `bin/preflight.sh` (Java, permessi 600 sull'env, DB raggiungibile,
keystore leggibile, password impostata, directory scrivibili, porta libera).

Bundle `squish-3.0.0.tar.gz` costruito e **verificato da pacchetto scompattato**, non dal repo.

### 3. Java 22 vs OEL 8.7 — indagine (nessuna modifica applicata)

OEL 8.7 non ha Java 22 nei repo. Verificato che **il codice non ne ha bisogno**: compilato con `--release 21`
→ BUILD SUCCESS, 143 test verdi, bytecode *major version 65* (= Java 21). I Virtual Thread, unica ragione
per cui era stato scelto 22, sono definitivi già in **21**.

- **Sblocco immediato senza toccare il codice**: `JAVA_HOME` si definisce **solo** in `config/squish.env`, e `squish-run.sh` la usa direttamente → una JDK da tarball sotto `/opt` funziona oggi, senza RPM e senza root.
- **Raccomandazione (non applicata)**: retarget a **Java 21 LTS**. Java 22 non è LTS ed è già fuori manutenzione; 21 è supportato fino al 2031 ed è nei repo Oracle Linux dalla 8.9. File da toccare: `pom.xml`, `.github/workflows/build.yml`, `Dockerfile`, i due `squish.env*.template`, `preflight.sh`, `INSTALL.md`, `README.md`, `CLAUDE.md`.
- Limite della verifica: i test sono girati su una JVM 22 con bytecode 21 (nessuna JDK 21 installata). Rischio residuo basso ma non nullo.

### 4. Perdita dati silenziosa sulla chiave di tracking → **Squish 3.1.0**

**Il difetto.** La detail ha PK composita `(OTTI_ID, OTTI_CTR)`: un `OTT_ID` possiede N parti.
Ma la tabella di tracking era su `OTT_ID` **da solo**, e così l'anti-join di resume. Appena UNA
parte andava in commit, l'intero documento risultava "fatto": una run interrotta a metà documento
condannava le parti residue a **non essere compresse mai più**, in silenzio, col tracking che
diceva `SUCCESS`.

L'`ORA-00001` di gennaio **era il sintomo**, non il bug: il passaggio `INSERT` → `MERGE` ha zittito
il canarino invece di correggere la grana. Da lì in poi nessun errore, da nessuna parte — perché
sulla tabella dati un `UPDATE` di colonna non chiave **non può** violare nulla.

**Numeri reali (FIDES @ FDS2, Oracle 11.2.0.4):** 2.391.414 righe di dettaglio su 2.259.811
documenti → **131.603 parti extra**; **128.382 documenti multi-parte**, fino a 14 parti.
`OTTI_CTR` **non è denso** (un documento ha parti con ctr da 1 a 5002): non assumere mai `1..N`.

**La correzione (3.1.0).** Tracking su `(OTT_ID, OTT_CTR)`, anti-join che sonda la riga di
dettaglio su entrambe le colonne, ogni `MERGE` con `OTT_CTR`. In più: **un `UPDATE` a zero righe
non può più diventare `SUCCESS`** — il watchdog fa rollback e registra `Failure`, la pipeline fa
rollback del batch e lo **rigioca un record alla volta**. Limite dichiarato: se il driver risponde
`SUCCESS_NO_INFO` sui batch il conteggio non è disponibile → **un solo WARN per run**, invece di
fingere. `recordUpdate()` spostato **dopo il commit**: prima contava le intenzioni.

**Forense sul danno pregresso.** Prima del 2026-01-10 17:23 (commit `29944e2`) l'`UPDATE` era
`WHERE OTTI_ID = ?` **senza ctr**: sovrascriveva TUTTE le parti con lo stesso BLOB. Ha girato in
produzione (tracking dall'08-01-2026). Misurato: 385 documenti multi-parte esposti, **47 con la
firma del danno** (parti sorelle di lunghezza BLOB identica) contro un rumore di fondo dello
**0,2%** sul gruppo di controllo dei mai-processati (74/37.193) → **~46 documenti realmente
danneggiati**, 61× sopra il baseline. Il vincolo `UNIQUE(OTT_ID)`, paradossalmente, **ha limitato
i danni**: il rollback del batch su `ORA-00001` ha impedito che fosse molto peggio.
→ **Decisione dell'utente (14/07/2026): NON ripristinare.** Archivio vecchio, non vale il costo.

**Come identificare il jar di un binario in produzione** (finché non c'è l'hash nel manifest):
`unzip -p squish.jar BOOT-INF/classes/.../CompressionPipeline.class | strings | grep "UPDATE %s SET"`
→ `WHERE %s = ?` = **jar distruttivo**; `WHERE %s = ? AND %s = ?` = fix presente.

**Rilascio.** `squish-3.1.0`. La tabella di tracking di FIDES (752.678 righe, forma vecchia) **deve**
essere migrata con `migrate_tracking_composite.sql` prima di riavviare, altrimenti ogni `MERGE`
fallisce. La migrazione assume **"parte già processata"** (marca `MIGRATED`): sbagliare per eccesso
costa un risparmio mancato, sbagliare per difetto **ricomprime un PDF già compresso** — e Squish è
lossy, quindi è danno irreversibile.

**La vista è opzionale, e ora lo è davvero.** `CREATE OR REPLACE VIEW SQUISH_STATS` era nuda sotto
`WHENEVER SQLERROR EXIT FAILURE`: senza `CREATE VIEW` → `ORA-01031` → **script abortito** dopo che
tabella/sequence/trigger erano già stati creati (il DDL fa autocommit). Riportava come fallito un
setup funzionante — e colpiva proprio chi seguiva il least-privilege, dato che
`create_squish_user.sql` revoca `CREATE VIEW`. Ora è in un blocco PL/SQL che tollera `ORA-01031`.

### Aperti (non risolti)

1. **Credenziali Oracle in chiaro** in `application-test.yml`, già in git history (`fidestest` @ `fraviapp024`). Vanno **ruotate**: spostarle in env var non le toglie dal passato.
2. **Il report e la dashboard dichiarano la modalità sbagliata**: mostrano `squish.mode`, non il profilo attivo. Con i default (`mode: AGGRESSIVE`, `active-profile: office`) dicono AGGRESSIVE mentre comprimono in MEDIUM. Il PDF che va al cliente riporta il dato falso.
3. **I fallimenti non vengono mai ritentati**: l'anti-join di resume è `NOT EXISTS (... WHERE OTT_ID = ...)` senza filtro sullo stato, quindi le righe in ERROR sono escluse **per sempre**. Un blip transitorio di Oracle orfana quei record definitivamente.
4. **`POST /api/thumbnail` è un placeholder** (disegna un rettangolo bianco con "Page N/T"). Rimosso dai materiali commerciali; da implementare o da togliere dalla release.
5. **`POST /api/config` non tocca la pipeline in esecuzione**: batch e watchdog fotografano il profilo nel costruttore. Influenza solo le chiamate successive a `/api/compress`.
6. **Niente è committato**: il working tree contiene il rename di package `pdf` → `squish` insieme a tutto il lavoro di queste sessioni. Da separare in commit distinti.

## Session 2026-01-10

### Composite Primary Key Support (OTTI_ID, OTTI_CTR)

**Problema**: La tabella OTTICAI ha una chiave primaria composita (OTTI_ID, OTTI_CTR), ma le UPDATE statement usavano solo OTTI_ID, causando aggiornamenti multipli errati.

**Errore riscontrato**: `ORA-00001: unique constraint violated` sulla tabella SQUISH_PROCESSED.

**Soluzione implementata**:

1. **SquishProperties.java**
   - Aggiunto `detailCtrColumn` con default `OTTI_CTR`
   - Getter/setter per la nuova proprietà

2. **PdfTask.java**
   - Aggiunto campo `ctr` al record `Data`
   - Signature: `Data(long id, long ctr, String filename, byte[] pdf)`

3. **CompressionResult.java**
   - Aggiunto `ctr` a tutti i record: `Success`, `Failure`, `Skipped`
   - Metodo `ctr()` nell'interfaccia sealed

4. **Squish.java**
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
java -jar target/squish-3.0.0.jar \
  --spring.profiles.active=prod \
  --squish.dry-run=true

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
