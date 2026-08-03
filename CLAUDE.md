# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Squish is a PDF compression pipeline. It reads PDF BLOBs out of an Oracle database, compresses them with iText 8, and writes them back in place. Java 22 (Virtual Threads), Spring Boot 3.2 for config and DI only, Micrometer/Prometheus for metrics.

## Build & Run

```bash
mvn clean package -DskipTests      # build executable jar -> target/squish-3.0.0.jar
mvn test                           # 143 unit tests, no DB or network needed
java -jar target/squish-3.0.0.jar --spring.profiles.active=prod
./build-dist.sh                    # tar/zip distribution bundle under target/
```

Run a single test class, method, or `@Nested` inner class:

```bash
mvn test -Dtest=SquishTest
mvn test -Dtest=SquishTest#compressesValidPdf
mvn test -Dtest='SquishTest$WatermarkTests'
```

Override any config property on the command line, e.g. `--squish.dry-run=true --squish.active-profile=archival`.

## Architecture

Three stages connected by bounded `ArrayBlockingQueue`s, all running on one
`Executors.newVirtualThreadPerTaskExecutor()`. `Semaphore`s (not thread pools) cap
real concurrency at `squish.pipeline.worker-threads`:

```
Producer (1 virtual thread, streams a JOIN of master+detail tables)
    ↓ BlockingQueue<PdfTask>
Workers (N virtual threads: validate magic bytes, compress with iText)
    ↓ BlockingQueue<CompressionResult.Success>
Writers (N virtual threads: batched UPDATE + MERGE, commit every batch-size)
```

Shutdown is by poison pill, and the two stages spell it differently — the producer
sends `PdfTask.Poison.INSTANCE`, while `CompressionPipeline.run()` signals writers by
enqueueing `CompressionResult.Success` records with `id == -1`. Writers detect the
sentinel by checking `result.id() == -1`, so `-1` is not a usable record ID.

`CompressionResult` is a sealed interface over `Success` / `Skipped` / `Failure`.
Only `Success` reaches the writers; `Skipped` and `Failure` are written to the
tracking table inline from the worker (`trackNonSuccess`), opening its own connection
per record.

### Two execution modes, two independent code paths

This is the single most important thing to know before changing pipeline behavior.

- **Batch mode** (default) uses `CompressionPipeline`, the producer/worker/writer
  design above.
- **Watchdog mode** (`squish.watchdog.enabled=true`) uses `WatchdogService`, which
  **does not use `CompressionPipeline` at all**. It re-implements DB reads, compression
  dispatch (`CompletableFuture` + `Semaphore`), and DB writes on its own.

A change to compression or persistence behavior almost always has to be made in
**both** files, or the two modes silently diverge.

### Shared state

`ProgressTracker` is a Spring singleton bean injected into the controllers,
`CompressionPipeline`, and `WatchdogService` — that's how the dashboard sees live
progress. `SquishMetrics` is a separate, genuinely static singleton
(`SquishMetrics.getInstance()`), written by `ProgressTracker` and scraped by `/metrics`.
It keeps its own Micrometer registry, independent of anything Spring provides.

### HTTP layer

Spring MVC on embedded Tomcat, with Thymeleaf templates and Spring Security.
Controllers live in `http/` (`StatusController`, `ConfigApiController`, `MetricsController`,
`CompressController`, `ThumbnailController`, `PageController`); response records are in
`http/dto/`; the dashboard and config pages are `resources/templates/*.html` plus
`resources/static/{css,js}`.

- **The JSON field names in `http/dto/` are a public contract.** The dashboard JS and
  external clients depend on them. `spring.jackson.default-property-inclusion: non_null`
  is required: the API previously used Gson, which omits nulls, and Jackson would
  otherwise start emitting `"activeProfile": null`.
- **Writes need authentication.** `POST /api/config`, `/api/compress` and `/api/thumbnail`
  require HTTP Basic (`squish.security.*`); everything else is public. Set
  `squish.security.enabled=false` for a closed LAN or for tests. With no
  `squish.security.password`, a random one is generated and logged at WARN.
- **CORS is off by default** — no wildcard. Add origins to `squish.http.cors-allowed-origins`
  to enable it.
- **Never render server data with `innerHTML`.** `filename` reaches the dashboard from the
  `OTT_NOME_FILE` column and is fully attacker-controlled. `dashboard.js` and `config.js`
  build every node with `createElement` + `textContent` for this reason.
- `WebServerConfig` maps `squish.http.*` onto Tomcat. If SSL is enabled but the keystore
  is unreadable, startup **fails** rather than silently serving plaintext.

### Batch mode must exit explicitly

Tomcat keeps the JVM alive, so `SquishApplication` calls `SpringApplication.exit(...)`
followed by `System.exit(...)` once `runBatchMode()` returns. Watchdog mode instead blocks
on a `CountDownLatch` until the shutdown hook fires. Removing that explicit exit makes a
batch run hang forever after finishing its work.

## Configuration model

All config binds to `SquishProperties` (`@ConfigurationProperties("squish")`) from
`src/main/resources/application{,-dev,-test,-prod}.yml`. The prod profile reads
everything from environment variables so credentials never appear in `ps aux`
(see `dist/config/squish.env.template`).

Compression settings resolve through `SquishProperties.getActiveCompressionProfile()`:
if `squish.active-profile` names a key under `squish.profiles`, that
`CompressionProfile` wins; otherwise it falls back to the legacy `squish.mode` enum.
A profile supplies either a `mode` or explicit `custom-scale-factor` /
`custom-jpeg-quality`, and additionally carries the watermark and PDF/A settings.

Actual `CompressionMode` values (`CompressionMode.java`) — note the README's table
disagrees and is wrong:

| Mode | Scale | JPEG quality |
|------|-------|--------------|
| `LOSSLESS` | 1.0 | 1.0 |
| `MEDIUM` | 0.75 | 0.7 |
| `AGGRESSIVE` | 0.5 | 0.3 |

## Non-obvious constraints

**Maven does not validate `<mainClass>`.** A wrong value there builds a jar whose
`Start-Class` points at a nonexistent class, and the failure only shows up at
`java -jar` time as a `ClassNotFoundException`. `mvn package` stays green. Worth
re-checking after any package rename.

**Oracle is required at startup, even for `--squish.dry-run=true`.** `BeanConfiguration`
declares `CompressionPipeline` as an eager `@Bean`, and its constructor builds a
`HikariDataSource`, which initializes its pool immediately. Dry-run only skips the
writer's `UPDATE`; it does not skip connecting. There is no in-memory or H2 fallback.

**Runtime profile switching affects the REST API but not the pipeline.** `POST /api/config`
mutates `SquishProperties.activeProfile` in memory only — nothing is written back to YAML,
so the switch is lost on restart. `/api/compress` resolves the profile per request and does
honor it, but `CompressionPipeline` and `WatchdogService` each snapshot
`getActiveCompressionProfile()` into their own `Squish` instance in their constructor, so a
running batch or watchdog keeps using the profile it started with. Relatedly, the
`Squish pdfCompressor` `@Bean` in `BeanConfiguration` is declared but never injected
anywhere — all three call sites build their own.

**`squish.mode` is what gets displayed, regardless of the active profile.** Startup
logs, `printSummary()`, and `StatusController`'s `compressionMode` field are all wired to
`properties.getMode()`. With the shipped defaults (`mode: AGGRESSIVE`,
`active-profile: office`) the dashboard says `AGGRESSIVE` while PDFs are compressed
with `MEDIUM`.

**The tracking table's columns are hardcoded to `OTT_ID` and `OTT_CTR`.**
`squish.query.id-column` / `detail-ctr-column` are interpolated into the source-table side
of the SQL, but every reference to the tracking table — the `NOT EXISTS` anti-join and every
`MERGE` in `CompressionPipeline` and `WatchdogService` — literally spells `OTT_ID` and
`OTT_CTR`. Renaming the config keys alone will not work.

**SQL is assembled with `String.format`, not bound parameters,** for table and column
names and for `squish.query.master-table-filter` (a raw WHERE fragment). Only `id-from`
/ `id-to` are bound. Treat all of `squish.query.*` as trusted configuration.

**The detail table has a composite primary key** `(OTTI_ID, OTTI_CTR)`, and one master
`OTT_ID` can own N detail rows. Every write is `WHERE detail-id-column = ? AND
detail-ctr-column = ?`, and `ctr` is threaded through `PdfTask` →
`Squish.compress(id, ctr, filename, bytes)` → `CompressionResult`.

**The tracking table is keyed on `(OTT_ID, OTT_CTR)` — one row per detail row, not per
document.** It used to be keyed on `OTT_ID` alone, which was a silent data-loss bug: the
resume anti-join also matched on `OTT_ID` alone, so once *any* part of a multi-part document
was committed, the *whole* document was excluded from every later run and parts 2..N were
never compressed — while the tracking table said `SUCCESS`. (The `ORA-00001` that once forced
the switch from `INSERT` to `MERGE` was this bug surfacing: `MERGE` silenced the crash without
fixing the grain.) The anti-join now probes the detail row on both columns; keep it that way.

**A zero-row `UPDATE` must never be recorded as `SUCCESS`.** The BLOB `UPDATE` and the
tracking `MERGE` share one transaction, so a tracking row is a *claim* that the BLOB was
rewritten. If the `UPDATE` matches no row (detail row deleted meanwhile), committing that
claim would make the anti-join hide the record forever. Both writers therefore check the row
count: `WatchdogService` rolls back and records a `Failure`; `CompressionPipeline` rolls the
batch back and replays it one record at a time. Note that Oracle's driver may answer
`Statement.SUCCESS_NO_INFO` for batched statements, in which case the check cannot run — that
is accepted, and warned about exactly once per run.

**Before the first run against a new schema**, create the tracking table:
`sqlplus user/pass@db @dist/sql/create_tracking_table.sql`. Records are skipped if they
already appear in it, so it doubles as the resume mechanism.

## Testing

JUnit 5 + AssertJ via `spring-boot-starter-test`. No database is ever needed: PDFs are
generated in-memory with iText (see the `TestPdfs` fixtures).

**Never use `@SpringBootTest` here.** The full context instantiates the eager
`CompressionPipeline` bean, whose constructor opens a HikariCP pool against Oracle, so
the test would fail without a database. The HTTP tests use `@WebMvcTest(controllers = …)`
plus `MockMvc`, with `SquishProperties` and `ProgressTracker` supplied by a nested
`@TestConfiguration`. Because `SquishProperties` is `@ConfigurationProperties`, the binder
will overwrite a hand-built instance from `application.yml` — the slices point at
`src/test/resources/http-slice-test.yml` to prevent that.

A compression test that needs a `Success` must use an image-bearing fixture
(`TestPdfs.withImage()`). A text-only PDF is already optimal, so the no-gain guard returns
`Skipped` — and iText will happily *inflate* a tiny synthetic PDF (a 1 KB fixture comes
out around 7 KB).

## Versioning

**`pom.xml` `<version>` is the single source of truth.** Bump it there and nothing else —
every other consumer derives it, so the old 2.0.0-vs-3.0.0 drift cannot come back:

- `build-dist.sh` parses the project `<version>` out of `pom.xml` with `awk`. It skips the
  `<parent>` block first, because Spring Boot's parent `<version>` (3.2.1) appears *earlier*
  in the file — a naive "first `<version>`" grep reads the wrong one.
- The `SquishApplication` ASCII banner resolves the version at runtime:
  `Package.getImplementationVersion()`, falling back to reading `Implementation-Version` from
  the jar manifest (Spring Boot's nested classloader leaves the package version `null` in a fat
  jar). Our manifest is identified by its `Start-Class`, not by iteration order, so a library's
  manifest can't be mistaken for ours. With no manifest at all (IDE / exploded classes) it prints
  `vdev` — never `null`. The box is fixed-width (63-char interior) and the version line is padded
  by `centerInBox()`, so a longer version string won't break the alignment.
- `Dockerfile` takes `ARG SQUISH_VERSION` (default `dev`); `docker-compose.yml` passes
  `${SQUISH_VERSION:-dev}` for both the build arg and the image tag. `build-dist.sh` writes a
  `.env` with the pom version into the dist bundle so compose picks it up automatically.

## CI

`.github/workflows/build.yml` runs a single `mvn clean verify -B`, so **failing tests fail the
build** and no jar artifact is uploaded. (It previously ran `package -DskipTests` and then a
separate `mvn test` with `continue-on-error: true` — CI stayed green with a red suite and shipped
the artifact anyway.) Surefire reports are uploaded on failure so a red run is diagnosable.
