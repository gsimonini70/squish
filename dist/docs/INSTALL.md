# Squish 3.0 - Installation and Operations Guide

Squish compresses PDF documents that are stored as BLOBs in an Oracle database and
writes the smaller version **back over the original row**. It ships as a single
executable JAR plus scripts, and runs either as a one-shot batch job or as a
long-running service that polls for new documents.

Read [Before you start](#before-you-start) first. Squish modifies your data in place.

---

## Contents

1. [Before you start](#before-you-start)
2. [Prerequisites](#prerequisites)
3. [What is in the bundle](#what-is-in-the-bundle)
4. [Quick start](#quick-start)
5. [Configuration](#configuration)
6. [Compression modes and profiles](#compression-modes-and-profiles)
7. [Security](#security)
8. [HTTPS](#https)
9. [Running as a service](#running-as-a-service)
10. [Operating](#operating)
11. [Monitoring](#monitoring)
12. [REST API](#rest-api)
13. [Adapting to your schema](#adapting-to-your-schema)
14. [Upgrading](#upgrading)
15. [Uninstalling](#uninstalling)
16. [Troubleshooting](#troubleshooting)

---

## Before you start

Four things that surprise people, in the order they usually bite:

| | |
|---|---|
| **Squish overwrites the source BLOB.** | There is no "output directory". The compressed PDF replaces the original in the detail table. **Take a backup of the table (or the tablespace) before your first real run.** |
| **A dry run still needs Oracle.** | `DRY_RUN=true` skips every database write, but the connection pool is created eagerly at startup. If Oracle is unreachable, Squish will not start, dry run or not. There is no offline/no-database trial mode. |
| **The tracking table is mandatory.** | It must exist before the first run (`sql/create_tracking_table.sql`). Every processed record is written to it, and records already in it are skipped. That is also how a run resumes after being interrupted. |
| **The dashboard can display the wrong compression mode.** | With the shipped defaults it says `AGGRESSIVE` while documents are actually compressed with `MEDIUM`. See [The mode vs. profile trap](#the-mode-vs-profile-trap). |

---

## Prerequisites

| Requirement | Notes |
|---|---|
| Java 22 or newer | Squish uses virtual threads. The start scripts refuse to run on anything older. Check with `java -version`. |
| Oracle database, reachable over JDBC | Any version supporting `MERGE` (11g and up). The bundled DDL is 11g-compatible. |
| Oracle account | `SELECT` on the master and detail tables, `UPDATE` on the BLOB column of the detail table, and — once — `CREATE TABLE` / `CREATE SEQUENCE` / `CREATE TRIGGER` / `CREATE VIEW` to create the tracking objects. For a dedicated least-privilege account, run `sql/create_squish_user.sql` (see [Quick start](#quick-start) step 2). |
| RAM | 2 GB minimum, 4 GB or more recommended. Each worker holds a whole PDF in memory twice (original + compressed). |
| A free TCP port | 8080 by default, for the dashboard and API. |

The Oracle JDBC driver is bundled inside the JAR. You do not need an Oracle client, except
for `sqlplus` to create the tracking table (any machine with `sqlplus` will do).

---

## What is in the bundle

`squish-3.0.0.tar.gz` (or `.zip` for Windows) unpacks to:

```
squish-3.0.0/
├── squish.jar                  # the application, self-contained
├── bin/
│   ├── install.sh              # installs to /opt/squish, creates the service user and unit
│   ├── preflight.sh            # pre-first-start checks. Run it before you start anything.
│   ├── squish-run.sh           # the foreground launcher; systemd, SysV and squish.sh all use it
│   ├── squish.sh               # start | stop | restart | status  (manual runs)
│   ├── uninstall.sh
│   └── install.bat, squish.bat, uninstall.bat     # Windows equivalents
├── config/
│   ├── squish.env.template     # >>> the file you edit <<<   (Linux/macOS)
│   ├── squish.env.bat.template # the same, for Windows
│   └── application.yml         # defaults, as ${ENV_VAR:default} placeholders; see below
├── service/
│   ├── squish.service          # systemd unit
│   └── squish.init             # SysV init script (RHEL 6 era systems)
├── sql/
│   ├── create_squish_user.sql
│   └── create_tracking_table.sql
├── docs/INSTALL.md             # this file
├── Dockerfile, docker-compose.yml, .env
├── logs/
└── reports/
```

`bin/squish-run.sh` is the single launcher: it sources `config/squish.env`, validates Java,
`DB_URL` and the JAR, creates `logs/` and `reports/`, then `exec`s the JVM so that a
supervisor sees the real PID and the real exit code. systemd, the SysV init script and
`bin/squish.sh` all go through it. You never need to invoke it directly.

---

## Quick start

About 15 minutes, ending in a real compression run. Linux/macOS; for Windows see
[Windows](#windows).

### 1. Install the files

```bash
tar xzf squish-3.0.0.tar.gz
cd squish-3.0.0
sudo ./bin/install.sh
```

`install.sh` must run as root. It creates the `squish` system user, copies everything to
`/opt/squish` (including `logs/` and `reports/`, owned by the service user), copies
`squish.env.template` to `/opt/squish/config/squish.env` with mode `600`, installs the
systemd unit (or the SysV init script on older systems) and symlinks `squish` into your
`PATH`. It does **not** start anything.

Want a different location? `INSTALL_DIR=/srv/squish sudo -E ./bin/install.sh` — but note
that the shipped systemd unit and init script hardcode `/opt/squish`, so you must edit
them to match.

### 2. Create the database user (optional but recommended)

Squish can run as any account that can read the master/detail tables and update the BLOB
column. If you would rather give it a **dedicated, least-privilege account** — the usual
choice in production — the kit ships one:

```bash
sqlplus sys/password@//dbhost:1521/SERVICE as sysdba @/opt/squish/sql/create_squish_user.sql
```

Edit the `DEFINE` block at the top first (user name, data owner, table and BLOB column
names, tablespace). The password is **prompted for**, never written into the file.

The account it produces holds `CREATE SESSION`, `SELECT` on the two source tables,
`UPDATE` on **one column only** (`OTTI_DATA`, the PDF BLOB), and ownership of its own
tracking table. Nothing else — no `RESOURCE`, no `DBA`, no `UNLIMITED TABLESPACE`. Squish
issues no `DELETE`, no `DROP` and no `ALTER`, and runs no DDL after setup, so the
column-level `UPDATE` grant makes it **structurally incapable** of touching any other data.

The script walks through four sections (create user → object grants → synonyms and tracking
table → revoke the setup-only DDL privileges). Follow them in order; it is idempotent and
safe to re-run.

> **Validate the account read-only first.** A dry run (step 6) never opens a write
> connection, so you can prove the account works with the `SELECT` grants alone, before
> granting `UPDATE` and before a single BLOB is rewritten.

### 3. Create the tracking table

Once per database, before the first run — connected as the account Squish will use:

```bash
sqlplus squish_user/password@//dbhost:1521/SERVICE @/opt/squish/sql/create_tracking_table.sql
```

This creates `SQUISH_PROCESSED` (plus a sequence, a trigger, two indexes and the
`SQUISH_STATS` view). Squish will not create it for you, and every run fails without it.
The script is idempotent — it tolerates objects that already exist (`ORA-00955`,
`ORA-01408`), so re-running it on a database that is already set up is safe and reports no
errors.

**One row per detail part, not per document.** A document (`OTT_ID`) can own several detail
rows, distinguished by `OTTI_CTR`, and each carries its own PDF. The tracking table mirrors that
grain exactly — `UNIQUE (OTT_ID, OTT_CTR)` — and so does the resume anti-join. It used to be
keyed on `OTT_ID` alone, which silently excluded every remaining part of a document as soon as
one part was committed. If you are upgrading from such an installation, see
[Upgrading](#upgrading): there is a migration script, and it must be run.

| Column | Meaning |
|---|---|
| `OTT_ID` | The source document. |
| `OTT_CTR` | The part within that document (`OTTI_CTR`). Unique together with `OTT_ID`. |
| `ORIGINAL_SIZE`, `COMPRESSED_SIZE` | Bytes. `COMPRESSED_SIZE` is NULL for skipped and failed records. |
| `SAVINGS_PERCENT` | |
| `STATUS` | `SUCCESS`, `SKIPPED` (not a PDF, or already optimal), `ERROR`, or `MIGRATED` (written only by the migration script — a part *assumed* already processed; see [Upgrading](#upgrading)) |
| `ERROR_MESSAGE` | Reason for the skip or failure |
| `PROCESSED_DATE`, `HOSTNAME` | Audit trail |

### 4. Configure

```bash
sudo -u squish vi /opt/squish/config/squish.env
```

At minimum, set the database connection — and set a dashboard password now rather than
later (see [Security](#security)):

```bash
export DB_URL=jdbc:oracle:thin:@//dbhost:1521/SERVICE
export DB_USER=squish_user
export DB_PASSWORD=...

export SECURITY_PASSWORD=...          # otherwise a random one is generated on every restart

export JAVA_HOME=/usr/lib/jvm/java-22
export SPRING_PROFILES_ACTIVE=prod    # required: this is what makes the variables above take effect
```

Then tell Squish which documents to touch. The default filter is almost certainly not
yours:

```bash
export MASTER_TABLE_FILTER="OTT_TIPO_DOC = '001030'"
```

Keep the file at mode `600` — it holds your database password.

### 5. Run the preflight check

```bash
sudo -u squish /opt/squish/bin/preflight.sh
```

Run this **before the first start, every time**. It checks the things that otherwise turn
into a support call: Java 22+, `squish.env` present and mode `600`, `DB_URL`/`DB_USER`/
`DB_PASSWORD` set, the database host and port actually reachable over TCP, the keystore
readable if `HTTP_SSL_ENABLED=true`, `SECURITY_PASSWORD` set, `logs/` and `reports/`
writable, the dashboard port free, and — if `sqlplus` is installed — that the tracking
table exists.

It prints `[ OK ]` / `[ WARN ]` / `[ FAIL ]` per check and **exits 1 if anything failed**.
Optional probes degrade to `[ SKIP ]` rather than failing. Fix every `FAIL` before going on;
`WARN` is advisory (an empty `SECURITY_PASSWORD` shows up here).

### 6. Rehearse with a dry run

A dry run reads and compresses, reports what it would have saved, and writes **nothing**:
no `UPDATE`, and no tracking rows either. It is safe to repeat and it does not consume your
resume state.

Run it in the foreground, bounded to a small ID range, so you can watch it:

```bash
cd /opt/squish
sudo -u squish sh -c '. ./config/squish.env; \
  "$JAVA_HOME/bin/java" -jar squish.jar --spring.profiles.active=prod \
    --squish.dry-run=true \
    --squish.watchdog.enabled=false \
    --squish.pipeline.id-from=1 --squish.pipeline.id-to=100'
```

Expect a `COMPRESSION SUMMARY` block on stdout with record counts, original vs. compressed
megabytes and the savings percentage. If you get an `ORA-` error instead, fix that before
going any further.

### 7. Run for real

The same command without `--squish.dry-run=true`, still bounded by an ID range for the
first pass. Check the result before widening it:

```sql
SELECT * FROM SQUISH_STATS;
```

When you are satisfied, drop the ID bounds and start the service — see [Running as a
service](#running-as-a-service).

---

## Configuration

### How settings reach the application

```
config/squish.env  ──sourced by bin/squish-run.sh──▶  environment variables
                                                              │
                     config/application.yml, and the prod profile inside the JAR,
                     resolve every setting as  key: ${ENV_VAR:default}
                                                              │
                                                              ▼
                                                      squish.* properties
```

**Edit `config/squish.env`. That is the whole configuration story.** Values reach the JVM
through the environment, never on the command line, so credentials never appear in
`ps aux`.

> ### Do not put literal values in `config/application.yml`
>
> That file is an *external* config location and it **outranks** the configuration packaged
> inside the JAR. Every tunable in it is deliberately written as a placeholder:
>
> ```yaml
> database:
>   jdbc-url: ${DB_URL:jdbc:oracle:thin:@//localhost:1521/ORCLPDB1}
> ```
>
> With the placeholder intact, `DB_URL` from `squish.env` wins and the default is only a
> fallback. **If you replace a `${VAR:default}` placeholder with a literal value, that
> literal wins and pins the setting — the matching variable in `squish.env` is then silently
> ignored.** (An earlier release shipped this file with literals in it, which quietly
> redirected every install at `localhost`.)
>
> Change a *default* here if you like. Pin a literal only when you mean "this setting is not
> configurable on this host", and expect `squish.env` to have no effect on it afterwards.

### Reference

| Variable | Property | Default | Notes |
|---|---|---|---|
| **Database** | | | |
| `DB_URL` | `squish.database.jdbc-url` | `jdbc:oracle:thin:@//localhost:1521/ORCLPDB1` | |
| `DB_USER` | `squish.database.username` | | |
| `DB_PASSWORD` | `squish.database.password` | | |
| **Which documents** | | | |
| `MASTER_TABLE` | `squish.query.master-table` | `OTTICA` | metadata table |
| `DETAIL_TABLE` | `squish.query.detail-table` | `OTTICAI` | holds the BLOB |
| `TRACKING_TABLE` | `squish.query.tracking-table` | `SQUISH_PROCESSED` | |
| `ID_COLUMN` | `squish.query.id-column` | `OTT_ID` | see [Adapting to your schema](#adapting-to-your-schema) |
| `FILENAME_COLUMN` | `squish.query.filename-column` | `OTT_NOME_FILE` | |
| `DETAIL_ID_COLUMN` | `squish.query.detail-id-column` | `OTTI_ID` | composite PK, part 1 |
| `DETAIL_CTR_COLUMN` | `squish.query.detail-ctr-column` | `OTTI_CTR` | composite PK, part 2 |
| `DATA_COLUMN` | `squish.query.data-column` | `OTTI_DATA` | the BLOB |
| `MASTER_TABLE_FILTER` | `squish.query.master-table-filter` | `OTT_TIPO_DOC = '001030'` | raw SQL `WHERE` fragment |
| **Compression** | | | |
| `ACTIVE_PROFILE` | `squish.active-profile` | `office` | named profile. **Wins over `COMPRESSION_MODE`.** |
| `COMPRESSION_MODE` | `squish.mode` | `AGGRESSIVE` | `LOSSLESS`, `MEDIUM`, `AGGRESSIVE`. Used only when no profile is active. |
| `DRY_RUN` | `squish.dry-run` | `false` | no database writes at all |
| **Pipeline** | | | |
| `WORKER_THREADS` | `squish.pipeline.worker-threads` | `16` (prod) | concurrent compressions, and the number of DB writers |
| `ID_FROM`, `ID_TO` | `squish.pipeline.id-from`, `.id-to` | `0`, `0` | `ID_TO=0` means no upper bound |
| **Watchdog** | | | |
| `WATCHDOG_ENABLED` | `squish.watchdog.enabled` | `true` (prod) | `true` = long-running poller, `false` = one-shot batch |
| `WATCHDOG_INTERVAL` | `squish.watchdog.poll-interval-seconds` | `300` | |
| **Dashboard and API** | | | |
| `HTTP_PORT` | `squish.http.port` | `8080` | |
| `SECURITY_ENABLED` | `squish.security.enabled` | `true` | `false` makes every endpoint public |
| `SECURITY_USERNAME` | `squish.security.username` | `admin` | |
| `SECURITY_PASSWORD` | `squish.security.password` | *(random, regenerated on every restart)* | **set this** |
| `CORS_ALLOWED_ORIGINS` | `squish.http.cors-allowed-origins` | *(empty — CORS off)* | comma-separated origins |
| `HTTP_SSL_ENABLED` | `squish.http.ssl-enabled` | `false` | see [HTTPS](#https) |
| `HTTP_KEYSTORE_PATH` | `squish.http.keystore-path` | | |
| `HTTP_KEYSTORE_PASSWORD` | `squish.http.keystore-password` | | |
| `HTTP_KEYSTORE_TYPE` | `squish.http.keystore-type` | `PKCS12` | or `JKS` |
| `HTTP_SSL_PROTOCOL` | `squish.http.ssl-protocol` | `TLSv1.3` | |
| **Reports and email** | | | |
| `REPORT_ENABLED` | `squish.report.enabled` | `true` | a PDF summary is written at the end of a run |
| `REPORT_DIRECTORY` | `squish.report.directory` | `reports` | relative to the install directory, or absolute. If you move it off `/opt/squish/reports`, see the [systemd](#systemd-current-linux-distributions) note. |
| `EMAIL_ENABLED` | `squish.email.enabled` | `true` (prod) | set `false` if you have no SMTP relay |
| `SMTP_HOST`, `SMTP_PORT` | | `587` | 587 = STARTTLS, 465 = direct SSL |
| `SMTP_USER`, `SMTP_PASSWORD` | | | leave empty for an unauthenticated relay |
| `SMTP_STARTTLS`, `SMTP_SSL` | | `true`, `false` | |
| `SMTP_FROM`, `SMTP_TO` | | | |
| **Process** | | | |
| `JAVA_HOME` | | | required by the systemd unit and the SysV init script |
| `JAVA_OPTS` | | `-Xms512m -Xmx4g -XX:+UseZGC` | |
| `SPRING_PROFILES_ACTIVE` | | `prod` | must be `prod` for the variables above to be read |

### The `MASTER_TABLE_FILTER`

It becomes the `WHERE` clause on the master table, verbatim. Any valid SQL fragment works:

```bash
export MASTER_TABLE_FILTER="OTT_TIPO_DOC IN ('001030','001031') AND OTT_STATUS = 'A'"
export MASTER_TABLE_FILTER="OTT_TIPO_DOC = '001030' AND CREATED_DATE > DATE '2024-01-01'"
export MASTER_TABLE_FILTER="OTT_TIPO_DOC = '001030' AND ROWNUM <= 10000"
```

For a NUMBER column holding a date as YYYYMMDD, compare numerically
(`... AND OTT_DATA_INS BETWEEN 20120101 AND 20121231`).

Because the filter is interpolated rather than bound, treat `squish.env` as privileged
configuration: whatever you put in it — filter, table names, column names — is executed as
SQL. Never build it from user input.

### Batch mode vs. watchdog mode

| | Batch (`WATCHDOG_ENABLED=false`) | Watchdog (`WATCHDOG_ENABLED=true`, the prod default) |
|---|---|---|
| Behaviour | Processes everything matching the filter, prints a summary, exits | Polls every `WATCHDOG_INTERVAL` seconds for records not yet in the tracking table, indefinitely |
| Ends when | The work is done. Exit code 0. | You stop it (`SIGTERM` / Ctrl-C) |
| Under systemd | The unit goes `inactive (dead)` after the run. That is success, not a fault. | Stays `active (running)` |
| Report | At the end of the run | On shutdown |
| Use it for | The initial backfill of an existing archive | Keeping up with newly ingested documents |

---

## Compression modes and profiles

The three modes, and their **actual** parameters:

| Mode | Image scale factor | JPEG quality | Typical use |
|---|---|---|---|
| `LOSSLESS` | 1.0 | 1.0 | Legal and archival documents. Structure-only optimisation. |
| `MEDIUM` | 0.75 | 0.7 | General office documents. |
| `AGGRESSIVE` | 0.5 | 0.3 | Maximum savings, visibly softer images. Test on a sample before committing. |

A **profile** bundles a mode (or explicit scale/quality values) with optional watermark and
PDF/A settings. These ship in the box:

| Profile | Compression | Extras |
|---|---|---|
| `archival` | `LOSSLESS` | converts the output to PDF/A-2b |
| `office` | `MEDIUM` | — (this is the **default**) |
| `web` | `AGGRESSIVE` | — |
| `custom-example` | scale 0.7, quality 0.65 | — |
| `confidential` | `MEDIUM` | stamps `CONFIDENTIAL` diagonally across every page at 30% opacity |

Select one with `ACTIVE_PROFILE=archival`. To ignore profiles entirely and let
`COMPRESSION_MODE` decide, set `ACTIVE_PROFILE` to an empty value.

Two caveats worth knowing before you pick one:

- The watermark is **burned into the stored PDF**, permanently — `confidential` is not a
  view-time overlay. If watermarking a page fails, Squish logs a warning and stores the
  document *without* the watermark rather than failing the record.
- PDF/A conversion runs after compression and rewrites the document. Combined with
  `LOSSLESS` the output can come out *larger* than the input. That is expected for archival
  use, but it means `archival` is not a way to save space.

### The mode vs. profile trap

**`ACTIVE_PROFILE` decides how documents are compressed, but the dashboard, the startup log
and the end-of-run summary all display `COMPRESSION_MODE`.**

With the shipped defaults (`COMPRESSION_MODE=AGGRESSIVE`, `ACTIVE_PROFILE=office`) the UI
reports `AGGRESSIVE` while every document is compressed with `MEDIUM` (0.75 / 0.7).

To make what you see match what you get, set both consistently:

```bash
export ACTIVE_PROFILE=web
export COMPRESSION_MODE=AGGRESSIVE
```

or drop the profile and drive everything from the mode:

```bash
export ACTIVE_PROFILE=
export COMPRESSION_MODE=MEDIUM
```

To see what is genuinely in force, ask the API — it reports the effective values:

```bash
curl -s http://localhost:8080/api/profile
# {"name":"office","mode":"MEDIUM","scaleFactor":0.75,"jpegQuality":0.7,...}
```

---

## Security

The dashboard and the read-only APIs are **public**. The write endpoints require HTTP Basic
authentication.

| Endpoint | Method | Auth |
|---|---|---|
| `/` (dashboard), `/config` (config page) | GET | public |
| `/api/status`, `/api/health`, `/api/profiles`, `/api/profile`, `/api/config` | GET | public |
| `/metrics` | GET | public |
| `/api/compress`, `/api/thumbnail`, `/api/config` | POST | **HTTP Basic** |

> ### Set `SECURITY_PASSWORD` before you start Squish
>
> If it is unset or blank, Squish generates a random UUID password at startup, logs it at
> `WARN`, and **generates a different one on every restart**:
>
> ```
> WARN ... Generated security password for user 'admin': 3f0c1e94-... - set squish.security.password to make it stable
> ```
>
> Any script or scheduled job that calls a write endpoint will break the next time the
> service restarts. Set it explicitly:
>
> ```bash
> export SECURITY_USERNAME=admin
> export SECURITY_PASSWORD='a-long-random-string'
> ```
>
> If you did not, recover the current one from the log:
>
> ```bash
> journalctl -u squish | grep 'Generated security password' | tail -1
> ```

Calling an authenticated endpoint:

```bash
curl -u admin:"$SECURITY_PASSWORD" -F file=@sample.pdf \
     http://localhost:8080/api/compress -o out.pdf
```

**Turning security off.** `SECURITY_ENABLED=false` makes every endpoint public, including
the ones that upload files and change the active profile. It is meant for tests and for a
closed management LAN. It is logged at `WARN` on startup.

**CORS is off by default.** No `Access-Control-Allow-Origin` header is emitted at all, and
there is no wildcard. A browser application on another origin cannot call the API until you
list its origin:

```bash
export CORS_ALLOWED_ORIGINS=https://portal.example.com,https://intranet.example.com
```

Allowed methods are then `GET`, `POST`, `OPTIONS`; allowed headers `Content-Type` and
`Authorization`; and the `X-Original-Size`, `X-Compressed-Size`, `X-Savings-Percent` and
`X-Duration-Ms` response headers are exposed to the browser. `curl` and server-to-server
callers are unaffected by any of this — CORS is a browser mechanism only.

**Uploads are capped at 50 MB** per file and per request, on both write endpoints.

---

## HTTPS

The dashboard serves plain HTTP unless you configure a keystore.

**If `HTTP_SSL_ENABLED=true` and the keystore is missing, unset, or unreadable, Squish
refuses to start.** This is deliberate. An earlier version silently fell back to plaintext
HTTP, which meant the dashboard password crossed the network in the clear while the
operator believed TLS was on. A failed start is the intended outcome — fix the keystore.

### 1. Create a keystore

```bash
sudo -u squish keytool -genkeypair -alias squish -keyalg RSA -keysize 2048 \
  -storetype PKCS12 -keystore /opt/squish/config/squish.p12 \
  -validity 3650 -storepass 'changeit' \
  -dname "CN=squish.example.com, OU=IT, O=Example, C=IT"

sudo chown squish:squish /opt/squish/config/squish.p12
sudo chmod 600 /opt/squish/config/squish.p12
```

The keystore must be readable **by the `squish` service user**, not just by root — that is
the most common cause of the fail-fast above.

For anything beyond a lab, import a certificate from your CA instead of self-signing.

### 2. Enable it

```bash
export HTTP_SSL_ENABLED=true
export HTTP_KEYSTORE_PATH=/opt/squish/config/squish.p12
export HTTP_KEYSTORE_PASSWORD=changeit
export HTTP_KEYSTORE_TYPE=PKCS12
export HTTP_SSL_PROTOCOL=TLSv1.3        # use TLSv1.2 if older clients must connect
```

The port does not change: the dashboard simply moves to `https://host:8080/`. The startup
log confirms it:

```
INFO  ... Web server configured on port 8080 (HTTPS, TLSv1.3)
```

---

## Running as a service

### systemd (current Linux distributions)

`bin/install.sh` has already installed and enabled the unit. Start it:

```bash
sudo systemctl start squish
sudo systemctl status squish
sudo journalctl -u squish -f
```

systemd is the **supported** supervision path. The unit runs `bin/squish-run.sh` as the
`squish` user, which sources `/opt/squish/config/squish.env` and execs the JVM — so systemd
tracks the real JVM PID and sees its real exit code. There is deliberately **no
`EnvironmentFile=`**: `squish.env` is a shell file (`export`, trailing comments) that
systemd's parser cannot read, and pointing `EnvironmentFile=` at it silently drops every
variable. Keep configuring through `squish.env`; do not add it back.

`Restart=on-failure` / `RestartSec=10` is the point of the whole arrangement. When Squish
hits an unrecoverable JVM error in watchdog mode — realistically an `OutOfMemoryError` — it
logs `FATAL` and **exits with status 1**, rather than staying up as a process that answers
the dashboard but silently processes nothing. systemd restarts it ten seconds later. If it
dies on every start (bad DB credentials, say), `StartLimitBurst=5` in 300 s stops the loop
and leaves the unit `failed`, loudly, instead of hot-looping. A crash loop from OOM means
you should raise the heap (`JAVA_OPTS="-Xms1g -Xmx8g -XX:+UseZGC"`) or lower
`WORKER_THREADS`.

Two things to know:

- The unit is hardened with `ProtectSystem=strict`: the filesystem is read-only to the
  service except for `/opt/squish/logs` and `/opt/squish/reports`, which are listed in
  `ReadWritePaths`. Both work out of the box. **If you point `REPORT_DIRECTORY` at some
  other path, you must add that path to `ReadWritePaths=` in the unit**, or every report
  write fails at the end of a run — long after anyone is watching.
- In batch mode the unit goes `inactive (dead)` once the run finishes. That is correct, not
  a failure.

### SysV init (RHEL/CentOS 6 and similar)

```bash
sudo service squish start
sudo service squish status
sudo tail -f /opt/squish/logs/squish.log
```

The init script backgrounds the same `bin/squish-run.sh` launcher as the service user, so
credentials still never appear in `ps aux`. It requires `DB_URL` and `JAVA_HOME` in
`squish.env`.

`status` returns proper LSB codes, which is what makes it usable from a monitor:

| Code | Meaning |
|---|---|
| `0` | running |
| `1` | **dead, but the PID file is still there — it crashed and nothing restarted it** |
| `3` | not running (stopped cleanly) |

> **A SysV init script cannot respawn a crashed process.** If Squish exits with status 1
> after a fatal error (see the systemd section above), **it stays down until something
> starts it again** — the dashboard simply stops answering. systemd is the supported path.
> On these hosts you must supervise it yourself. The init script ships a `check` action for
> exactly this — it starts Squish only if it is not running, and is silent when it is, so it
> will not mail root every minute:
>
> ```cron
> * * * * * /etc/init.d/squish check >> /opt/squish/logs/respawn.log 2>&1
> ```
>
> `install.sh` prints this line for you when it installs the SysV path. Worst-case downtime
> is one minute. For true respawn, let `init` supervise the launcher directly via
> `/etc/inittab`:
>
> ```
> sq:2345:respawn:/bin/su -s /bin/sh squish -c /opt/squish/bin/squish-run.sh
> ```
>
> `monit` and `supervisord` work too, as does alerting on the Prometheus target going down.

### Manual, no init system

```bash
cd /opt/squish
./bin/squish.sh start        # also: stop, restart, status
```

It backgrounds `bin/squish-run.sh` and writes `/opt/squish/squish.pid`. The application log
is `/opt/squish/logs/squish.log`; startup output that precedes logging (a bad `JAVA_HOME`,
a missing `squish.env`) lands in `/opt/squish/squish.log`. Nothing restarts it if it dies —
`squish.sh status` will tell you it is `DEAD but squish.pid exists`, which means it
crashed.

### Windows

```batch
mkdir C:\squish
REM unpack the bundle into C:\squish

copy C:\squish\config\squish.env.bat.template C:\squish\config\squish.env.bat
notepad C:\squish\config\squish.env.bat

cd C:\squish
bin\squish.bat start         REM also: stop, status
```

`squish.bat` reads `config\squish.env.bat` (which uses `set`, not `export`), creates `logs\`
and `reports\`, starts the JVM in the background, and logs to `C:\squish\squish.log`.
Restrict the ACL on `squish.env.bat` — it holds your database password.

**Nothing supervises that process**, and Squish exits with code 1 on a fatal error, so for
unattended use run it as a service under [NSSM](https://nssm.cc/) — and give it the same
restart guarantee systemd has:

```batch
nssm install Squish "%JAVA_HOME%\bin\java.exe"
nssm set Squish AppParameters "-jar C:\squish\squish.jar --spring.profiles.active=prod"
nssm set Squish AppDirectory "C:\squish"
nssm set Squish AppExit Default Restart
nssm set Squish AppRestartDelay 10000
```

Without `AppExit Default Restart`, a fatal exit leaves the service stopped. `install.bat`
prints these lines for you.

Create the tracking table exactly as on Linux, from any host that has `sqlplus`.

### Docker

```bash
docker compose up -d          # reads the bundled .env for SQUISH_VERSION, tags squish:3.0.0
docker compose logs -f
```

`docker-compose.yml` passes `DB_URL`, `DB_USER` and `DB_PASSWORD` through from the
environment, mounts `./logs` and `./config`, sets `restart: unless-stopped` (so a fatal exit
is restarted, as under systemd), and health-checks `GET /api/health`. Add
`SECURITY_PASSWORD` and any other variables to the `environment:` block.

---

## Operating

| Task | systemd | SysV | manual |
|---|---|---|---|
| Start | `systemctl start squish` | `service squish start` | `bin/squish.sh start` |
| Stop | `systemctl stop squish` | `service squish stop` | `bin/squish.sh stop` |
| Status | `systemctl status squish` | `service squish status` | `bin/squish.sh status` |
| Logs | `journalctl -u squish -f` | `tail -f /opt/squish/logs/squish.log` | `tail -f /opt/squish/logs/squish.log` |
| Start at boot | `systemctl enable squish` | `chkconfig squish on` | — |
| Restart if crashed | automatic | `/etc/init.d/squish check` (from cron) | — |

The application log is `/opt/squish/logs/squish.log` in every mode (rotated at 10 MB, 7
files kept); under systemd it also goes to the journal.

Stopping is graceful in every case: the process receives `SIGTERM`, finishes the documents
it is holding, writes its final report, and exits. Give it up to 30 seconds before assuming
it is stuck.

### Checking progress from the database

```sql
SELECT * FROM SQUISH_STATS;                                     -- totals by status
SELECT COUNT(*) FROM SQUISH_PROCESSED WHERE STATUS = 'ERROR';
SELECT OTT_ID, ERROR_MESSAGE FROM SQUISH_PROCESSED
 WHERE STATUS = 'ERROR' ORDER BY PROCESSED_DATE DESC FETCH FIRST 20 ROWS ONLY;
```

### Re-processing records

Squish skips any record already in the tracking table — **including ones that were skipped
or that failed.** Nothing is ever retried automatically. To force a retry, delete the rows
first:

```sql
DELETE FROM SQUISH_PROCESSED WHERE STATUS = 'ERROR';
COMMIT;
```

Deleting `SUCCESS` rows makes Squish re-compress documents that are already compressed —
lossy modes will degrade them a second time. Do not do it unless you mean it.

---

## Monitoring

### Dashboard

`http://<host>:8080/` — live throughput, records processed, savings, recent activity, JVM
memory and CPU. It polls `/api/status`, is read-only, and needs no login.
`http://<host>:8080/config` shows the configured profiles and lets you switch the active one
(that POST does require the password).

### JSON endpoints

| Endpoint | Returns |
|---|---|
| `GET /api/health` | `{"status":"UP","state":"RUNNING"\|"COMPLETED"}` — use this for load-balancer and container health checks |
| `GET /api/status` | Progress counters, throughput, `compressionMode`, system info, recent activity |
| `GET /api/profile` | The **effective** compression settings (see [the trap](#the-mode-vs-profile-trap)) |
| `GET /api/profiles` | All configured profiles |
| `GET /api/config` | The current configuration as the UI sees it |

### Prometheus

`GET /metrics` returns the Prometheus text exposition format, unauthenticated. Point a
scrape job at it:

```yaml
scrape_configs:
  - job_name: squish
    static_configs:
      - targets: ['squish-host:8080']
```

Exposed series (all tagged `application="squish"`):

| Metric | Type | |
|---|---|---|
| `squish_records_read_total` | counter | records fetched from Oracle |
| `squish_records_compressed_total` | counter | successfully compressed |
| `squish_records_skipped_total` | counter | not a PDF, or no gain |
| `squish_records_failed_total` | counter | **alert on this** |
| `squish_bytes_original_total`, `squish_bytes_compressed_total` | counter | |
| `squish_savings_percent`, `squish_compression_ratio` | gauge | |
| `squish_queue_size`, `squish_active_workers` | gauge | pipeline backpressure |
| `squish_watchdog_cycle` | gauge | increments once per poll. **If it stops climbing, the watchdog is stuck.** |
| `squish_compression_duration` | timer | per document |
| `squish_jvm_memory_used_bytes`, `squish_jvm_memory_max_bytes`, `squish_jvm_cpu_usage`, `squish_jvm_threads_active` | gauge | |

Two alerts earn their keep: `squish_records_failed_total` rising, and `up == 0` for the
target — which is exactly what a fatal exit looks like on a SysV box, where nothing restarts
it.

### Reports and email

At the end of a batch run — or at shutdown in watchdog mode — Squish writes a PDF summary to
`REPORT_DIRECTORY` and, if `EMAIL_ENABLED=true`, mails it to `SMTP_TO`. If you have no SMTP
relay, set `EMAIL_ENABLED=false`; the prod default is `true`.

---

## REST API

Independent of the database pipeline: it compresses a PDF you upload and hands it straight
back. Nothing is read from or written to Oracle, and nothing is recorded in the tracking
table. Both endpoints require HTTP Basic auth and accept `multipart/form-data`, up to 50 MB.

### `POST /api/compress`

| Field | Required | Meaning |
|---|---|---|
| `file` | yes | the PDF, as a multipart file part |
| `profile` | no | a named profile (`archival`, `office`, `web`, …). Defaults to the active profile; an unknown name silently falls back to it. |
| `password` | no | password to **open** an encrypted input PDF |
| `outputPassword` | no | encrypt the returned PDF with AES-256 using this password |
| `format` | no | `binary` (default) or `json` |

The binary response is `application/pdf` as an attachment, with:

| Header | |
|---|---|
| `X-Original-Size` | bytes in |
| `X-Compressed-Size` | bytes out |
| `X-Savings-Percent` | e.g. `41.20` |
| `X-Duration-Ms` | |

```bash
curl -u admin:"$SECURITY_PASSWORD" \
     -F file=@invoice.pdf -F profile=web \
     -D headers.txt -o invoice_compressed.pdf \
     http://localhost:8080/api/compress
```

With `format=json` you get the same figures plus the document itself, Base64-encoded, in a
`data` field — convenient for callers that cannot handle a binary body.

If the PDF is already optimal, Squish returns it **unchanged**, with `200` and 0% savings —
not an error. A non-PDF upload returns `400`; a genuinely broken or unreadable PDF returns
`500` with a JSON error body.

### `POST /api/thumbnail`

> **This does not render the page.** It returns a correctly sized, correctly proportioned
> **placeholder**: a white rectangle with a thin border and the text `Page N/T` in the
> middle. It is a stub, useful only for wiring up a client. Do not put it in front of users
> as a document preview.

### `POST /api/config`

Switches the active profile at runtime:

```bash
curl -u admin:"$SECURITY_PASSWORD" -H 'Content-Type: application/json' \
     -d '{"activeProfile":"archival"}' http://localhost:8080/api/config
```

Its reach is narrower than it looks, and the limits are not obvious:

- The change is **in memory only**. It is not written back to any file, and it is lost on
  restart.
- It affects **subsequent `/api/compress` calls only.** A batch run or a watchdog already in
  flight captured its profile at startup and keeps using it. To change how the pipeline
  compresses, edit `ACTIVE_PROFILE` in `squish.env` and restart the service.

---

## Adapting to your schema

Squish expects two tables: a **master** table with one row per document (id, filename), and a
**detail** table holding the PDF bytes, keyed by a **composite primary key**
(`DETAIL_ID_COLUMN`, `DETAIL_CTR_COLUMN`). Every update targets
`WHERE detail_id = ? AND detail_ctr = ?`. If your detail table has a single-column key,
there is no supported configuration for it — the second key column is not optional.

> **`OTT_ID` is hardcoded in the tracking table.** `ID_COLUMN` renames the column on the
> *source* side only. Every reference to the tracking table — the anti-join that implements
> resume, and the upserts that record results — spells the column `OTT_ID` literally.
> **Changing `ID_COLUMN` alone will not work.** Keep the tracking table's own ID column named
> `OTT_ID`, exactly as `create_tracking_table.sql` creates it, whatever the source column is
> called. Only `TRACKING_TABLE` (the table *name*) is genuinely configurable; if you rename
> it, edit the `CREATE TABLE` in the DDL to match.

Table and column names are interpolated into the SQL as text, not bound as parameters. They
come from privileged configuration, so this is safe exactly as long as `squish.env` is
(mode `600`, owned by root or the service user).

---

## Upgrading

> ### Upgrading from a release whose tracking table has no `OTT_CTR` column
>
> **Stop Squish, and run `sql/migrate_tracking_composite.sql` before you start it again.**
> Skipping it is not an option: the new code writes `OTT_CTR` on every `MERGE`, so every write
> fails against the old table.
>
> The release it upgrades from had a silent data-loss bug. The tracking table was keyed on
> `OTT_ID` alone, and so was the resume anti-join — so as soon as **one** part of a multi-part
> document was committed, the **whole** document was excluded from every later run. Parts 2..N
> were never compressed, while the tracking table reported `SUCCESS`.
>
> The migration cannot undo that. An old tracking row proves *some* part of the document was
> processed but never recorded **which**, and that information is gone. So the backfill assumes
> **already processed** and marks the sibling parts `MIGRATED`. That direction is deliberate:
> wrongly assuming "done" leaves a part uncompressed (a missed saving, recoverable at any time),
> whereas wrongly assuming "not done" makes Squish compress an already-compressed PDF a second
> time — and Squish is lossy, so that is **irreversible damage**. The script's header explains
> how to recover the missed savings later, deliberately and per document.
>
> The script needs DDL privileges (`ALTER TABLE`). If you followed
> [step 2](#quick-start) and revoked them after setup, grant them back for the duration and
> revoke them again afterwards.

```bash
sudo systemctl stop squish                       # or: sudo service squish stop

sudo cp /opt/squish/squish.jar /opt/squish/squish.jar.bak
sudo cp /opt/squish/config/squish.env ~/squish.env.bak

tar xzf squish-3.0.0.tar.gz
sudo cp squish-3.0.0/squish.jar /opt/squish/squish.jar

# the scripts, the service unit and config/application.yml change between releases too
sudo cp squish-3.0.0/bin/*.sh          /opt/squish/bin/
sudo cp squish-3.0.0/config/application.yml /opt/squish/config/
sudo cp squish-3.0.0/service/squish.service /etc/systemd/system/   # then: systemctl daemon-reload
sudo chown -R squish:squish /opt/squish
sudo chmod +x /opt/squish/bin/*.sh

# new releases add variables; your live squish.env is never overwritten
diff squish-3.0.0/config/squish.env.template /opt/squish/config/squish.env

sudo -u squish /opt/squish/bin/preflight.sh
sudo systemctl daemon-reload && sudo systemctl start squish
sudo journalctl -u squish -n 50
```

Copy `config/application.yml` too — **unless you have pinned literals in it**, in which case
merge by hand and keep your pins. Because `squish.env` is never touched by an upgrade, new
settings arrive at their defaults, which is why that `diff` is worth the minute it takes.
Coming from a version older than 3.0, the two that will change your behaviour are
`SECURITY_PASSWORD` (authentication is now on by default) and `ACTIVE_PROFILE` (profiles now
override `COMPRESSION_MODE`).

The tracking table has not changed. Re-running the DDL is harmless if you want the
reassurance — it is idempotent. Documents already compressed stay compressed and are not
touched again.

---

## Uninstalling

```bash
sudo /opt/squish/bin/uninstall.sh
```

It stops and disables the service, removes the unit or init script and the `squish` symlink,
deletes `/opt/squish/bin` and `/opt/squish/service`, and then **asks** before removing your
configuration, your logs, and the `squish` service user.

It does not touch the database: the tracking table and the compressed documents remain. To
remove the tracking objects:

```sql
DROP VIEW SQUISH_STATS;
DROP TABLE SQUISH_PROCESSED;
DROP SEQUENCE SQUISH_PROCESSED_SEQ;
```

Be clear that **compression is not reversible.** Uninstalling Squish does not restore the
original PDFs; only a database restore does.

---

## Troubleshooting

### The application will not start

**Run `bin/preflight.sh` first** — it catches most of the table below in one shot, before
the JVM even starts. Then read the first 50 lines of the log: startup failures are loud and
specific.

| Message | Cause | Fix |
|---|---|---|
| `keystore is not readable at: ...`, or `ssl-enabled=true but keystore-path is not set` | SSL is on but the keystore is missing, misspelled, or unreadable by the `squish` user. **Squish refuses to start rather than fall back to plaintext HTTP** — that is intentional. | Fix the path; `chown squish:squish`, `chmod 600`. Or set `HTTP_SSL_ENABLED=false`. See [HTTPS](#https). |
| `ORA-12541`, `ORA-01017`, `The Network Adapter could not establish the connection`, or a connection-pool timeout | Oracle unreachable, or the credentials are wrong. **This stops a dry run too**: the pool is created eagerly at startup, before any mode check. | Verify `DB_URL`, `DB_USER`, `DB_PASSWORD`. Test with `sqlplus` from the same host as the same user. Check the firewall. |
| `ORA-00942: table or view does not exist` | The tracking table was never created; or `MASTER_TABLE` / `DETAIL_TABLE` are wrong; or the DB user cannot see them. | Run `sql/create_tracking_table.sql`. Confirm the tables exist under the schema the user connects as (add a schema prefix if they do not). |
| `Port 8080 already in use` | Something else holds the port. | `export HTTP_PORT=8081`. |
| `ERROR: Java 22 or higher required` | The launcher found an older JVM. | Point `JAVA_HOME` in `squish.env` at a Java 22+ installation. |
| `ERROR: DB_URL is not set in ...` | `bin/squish-run.sh` validates before launching the JVM. | Set it in `squish.env`. |
| `ERROR: environment file not found` | `config/squish.env` does not exist. | `cp config/squish.env.template config/squish.env && chmod 600 config/squish.env` |
| A setting in `squish.env` is simply ignored | Someone replaced its `${VAR:default}` placeholder in `config/application.yml` with a literal, which pins it. | Restore the placeholder. See [Configuration](#how-settings-reach-the-application). |

### I cannot authenticate against `/api/compress` (401)

You almost certainly never set `SECURITY_PASSWORD`, so a **new random password was generated
at the last restart.** Recover it:

```bash
journalctl -u squish | grep 'Generated security password' | tail -1
# or: grep 'Generated security password' /opt/squish/logs/squish.log
```

Then stop relying on it: set `SECURITY_PASSWORD` in `squish.env` and restart, or the next
restart will break your clients again. See [Security](#security).

A `401` on a `GET` you expected to be public means you hit a path that is not on the public
list — only the endpoints in the [Security](#security) table are open.

### The dashboard shows a compression mode I did not configure

Working as designed, badly. The dashboard displays `COMPRESSION_MODE`, but `ACTIVE_PROFILE`
is what is actually applied. The defaults are `AGGRESSIVE` and `office` (= `MEDIUM`), so the
dashboard says `AGGRESSIVE` while your documents get `MEDIUM`.

`curl -s http://localhost:8080/api/profile` reports the truth. To align them, see [the
trap](#the-mode-vs-profile-trap).

### It runs, but processes zero records

In order of likelihood:

1. **Everything is already in the tracking table.** That is the resume mechanism doing its
   job. `SELECT * FROM SQUISH_STATS;` — if the counts match your corpus, there is nothing
   left to do.
2. **`MASTER_TABLE_FILTER` matches nothing.** Run it by hand:
   `SELECT COUNT(*) FROM OTTICA WHERE <your filter>;`
3. **`ID_FROM` / `ID_TO` exclude everything.** `ID_TO=0` means "no upper bound"; any other
   value is a real ceiling, often left over from a test run.
4. **You are still in dry run.** `DRY_RUN=true` writes nothing at all — not even tracking
   rows — so the database looks untouched afterwards. That is correct behaviour.

### Records land in the tracking table as `SKIPPED`

`SKIPPED` is not an error. Either the BLOB does not start with `%PDF-` (it is not a PDF), or
the PDF is already optimal and compressing it would only make it bigger. Squish leaves the
original alone in both cases; `ERROR_MESSAGE` tells you which.

Password-protected PDFs are a different matter: the pipeline has no password to open them
with, so they fail and are recorded as `ERROR`. There is no configuration for supplying one
— only the REST API accepts a `password` field.

### The service died, or stopped processing

On an unrecoverable JVM error — in practice an `OutOfMemoryError` — the watchdog logs `FATAL`
and **exits with status 1** on purpose, so that a supervisor can restart it clean:

```
ERROR ... FATAL: cycle #42 threw OutOfMemoryError - the watchdog cannot continue safely
ERROR ... Watchdog terminating with exit code 1 after unrecoverable error
```

- **systemd / Docker / NSSM** (with `AppExit Default Restart`): restarted automatically, ten
  seconds later. Repeated restarts in `journalctl` mean a crash loop — raise `-Xmx` in
  `JAVA_OPTS`, or cut `WORKER_THREADS`. Each worker holds a whole PDF in memory twice, so 16
  workers against 100 MB scans needs a large heap. If systemd reports the unit `failed` and
  has stopped trying, it hit `StartLimitBurst` (5 failed starts in 300 s): fix the cause,
  then `systemctl reset-failed squish && systemctl start squish`.
- **SysV init**: **nothing restarts it.** `service squish status` returns `1` — "dead but
  PID file exists" — which is precisely the crashed state. It stays down until something
  starts it: the `check` action from cron, if you set it up. See
  [SysV init](#sysv-init-rhelcentos-6-and-similar).

If the process is alive but nothing progresses, check that `squish_watchdog_cycle` is still
climbing in `/metrics`, and look for `ORA-` errors in the log: a database that stops answering
will stall the pipeline without killing it.

### A browser app gets a CORS error

Expected — CORS is off by default and there is no wildcard. Add the calling origin to
`CORS_ALLOWED_ORIGINS` (exact scheme, host and port; comma-separated) and restart. Only `GET`,
`POST` and `OPTIONS` are permitted, with the `Content-Type` and `Authorization` headers.

Sending `Authorization` cross-origin over plain HTTP will also make browsers complain about
the credentials themselves — enable [HTTPS](#https) if the API is called from a browser on
another host.

### Compression saves less than expected

Text-only PDFs are already efficiently encoded; the savings come from re-encoding images. A
run over born-digital documents with no images will legitimately report single-digit savings,
and many records will come back `SKIPPED` (no gain). That is Squish correctly declining to
make files bigger. Scanned documents are where `AGGRESSIVE` (0.5 scale, 0.3 quality) earns its
keep — but check a sample for legibility before committing to it across an archive, because it
cannot be undone.

---

## Getting support

Include all of these. Without them, the first reply will only ask for them:

- The Squish version, from the first lines of the log banner.
- The startup log, from the banner through the 50 lines after it (redact passwords).
- `curl -s http://localhost:8080/api/status` and `curl -s http://localhost:8080/api/profile`.
- `SELECT STATUS, COUNT(*) FROM SQUISH_PROCESSED GROUP BY STATUS;`
- Your `squish.env`, **with `DB_PASSWORD`, `SMTP_PASSWORD`, `SECURITY_PASSWORD` and
  `HTTP_KEYSTORE_PASSWORD` removed.**
