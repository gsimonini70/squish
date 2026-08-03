# SQUISH 3.0

## La Soluzione Enterprise per la Compressione Intelligente dei PDF

*Lucsartech Srl — Fidenza (PR)*

---

## Il Problema

Le aziende con grandi archivi documentali gestiscono volumi crescenti di file PDF:

- **Archivi documentali** che crescono in modo continuo
- **Costi di storage** in aumento, on-premise e in cloud
- **Tempi di trasferimento e backup** elevati per documenti pesanti
- **Conformità normativa** (PDF/A) sempre più richiesta per l'archiviazione a lungo termine
- **Gestione manuale** inefficiente e soggetta a errori

Una quota rilevante di questi archivi è costituita da documenti scansionati e ricchi di
immagini, spesso salvati senza alcuna ottimizzazione: è proprio qui che si concentra il
potenziale di risparmio.

---

## La Soluzione: SQUISH

**SQUISH** è una piattaforma enterprise per la compressione automatizzata dei PDF,
progettata per integrarsi nativamente con i sistemi documentali aziendali basati su
Oracle Database e per operare in modo sicuro e non distruttivo.

### Vantaggi Chiave

| Beneficio | Descrizione |
|-----------|-------------|
| **Riduzione Storage** | Risparmio significativo sui documenti ricchi di immagini |
| **Automazione Completa** | Elaborazione massiva senza intervento manuale |
| **Nessuna Perdita Involontaria** | Se la compressione non riduce il file, l'originale è preservato |
| **Conformità PDF/A** | Archiviazione a norma per il lungo termine |
| **Integrazione Nativa** | Oracle DB, metriche Prometheus, dashboard real-time |
| **API-first** | Una chiamata HTTP ottimizza, protegge e filigrana i PDF anche dove non era previsto |

> Nota sulle percentuali: il risparmio reale **dipende dal contenuto**. Documenti
> scansionati e ricchi di immagini possono ridursi in modo marcato; i PDF di solo
> testo, già compatti, guadagnano poco o nulla. SQUISH non forza mai una riscrittura
> in perdita quando non c'è un guadagno effettivo.

---

## Funzionalità Principali

### 1. Compressione Multi-Profilo Configurabile

SQUISH offre modalità di compressione con parametri **espliciti e verificabili** di
scala immagine e qualità JPEG. La compressione agisce ricomprimendo le immagini
raster contenute nel PDF; sui documenti di solo testo non c'è ricompressione delle
immagini e la struttura viene solo ottimizzata.

| Modalità | Scala immagine | Qualità JPEG | Scopo |
|----------|----------------|--------------|-------|
| **LOSSLESS** | 1.0 (100%) | 1.0 (100%) | Integrità e conformità: nessuna ricompressione delle immagini, sola ottimizzazione della struttura. Risparmio marginale. |
| **MEDIUM** | 0.75 (75%) | 0.70 (70%) | Documenti d'ufficio: buon equilibrio tra dimensione e qualità visiva. |
| **AGGRESSIVE** | 0.50 (50%) | 0.30 (30%) | Web/email: massima riduzione, con calo di qualità percepibile. |
| **CUSTOM** | configurabile | configurabile | Scala e qualità JPEG impostate esplicitamente per esigenze specifiche. |

**Profili preconfigurati** (`archival`, `office`, `web`, `custom`) mappano queste
modalità su casi d'uso concreti, con eventuale watermark e PDF/A associati.

#### Misure indicative su documenti campione

Su file di prova sintetici (piccoli, quindi **non** benchmark di produzione) la
compressione in modalità standard ha prodotto:

| Contenuto del PDF | Prima | Dopo | Risparmio |
|-------------------|-------|------|-----------|
| Immagine a colori (RGB) | 4.652 byte | 3.593 byte | ~23% |
| Immagine in scala di grigi | 3.595 byte | 2.829 byte | ~21% |
| Solo testo | invariato | invariato | 0% (originale preservato) |

Su archivi reali di documenti scansionati il risparmio tende a essere sensibilmente
più elevato; su PDF già ottimizzati o di solo testo è trascurabile. La misura precisa
si ottiene con un assessment sui documenti effettivi del cliente.

### 2. Conformità PDF/A per Archiviazione Legale

Conversione ai formati di archiviazione a lungo termine:

- **PDF/A-1B** - Conformità base, massima compatibilità
- **PDF/A-2B** - Standard ISO 32000-1, supporto JPEG2000 *(consigliato)*
- **PDF/A-3B** - Permette allegati incorporati

Caratteristiche:
- Embedding automatico del profilo colore sRGB ICC
- Generazione dei metadati XMP
- Metadati di documento (producer, creator)
- Compatibile con compressione e watermarking

### 3. Watermarking Professionale

Filigrane configurabili applicate durante la compressione:

- **Posizioni**: CENTER, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, DIAGONAL, TILED
- **Personalizzazione**: testo, font, dimensione, colore (hex), opacità (0.0-1.0)
- **Casi d'uso**: "CONFIDENZIALE", "BOZZA", "COPIA", diciture aziendali

### 4. Sicurezza

- **Lettura di PDF crittografati** protetti da password
- **Ri-cifratura dell'output** con AES-256
- **Credenziali protette** - passate via variabili d'ambiente, non visibili in `ps aux`
- **HTTPS/TLS** con keystore PKCS12 o JKS

### 5. Elaborazione Massiva ad Alte Prestazioni

Architettura moderna su **Java 22 Virtual Threads** (Project Loom):

```
┌─────────────────────────────────────────────────────┐
│  Producer          Worker Pool         Writer Pool  │
│  (DB Read)    →    (Compress)     →    (DB Write)  │
│                                                     │
│  Thread virtuali + code bounded + semafori          │
└─────────────────────────────────────────────────────┘
```

- Modello produttore / worker / writer con code a capacità limitata (backpressure)
- Numero di worker configurabile
- Elaborazione I/O-bound efficiente grazie ai thread virtuali

Il throughput effettivo dipende dalla dimensione dei documenti, dalla latenza del
database e dalle risorse del server: va misurato nell'ambiente del cliente.

### 6. REST API — Ottimizzare Anche Dove Non Era Previsto

Non tutti i documenti nascono dentro il gestionale. Arrivano da un portale web, da un
applicativo di terze parti, da un ufficio che li carica a mano, da un fornitore via
e-mail. Sono proprio quelli che restano fuori da ogni processo di ottimizzazione,
perché integrarli richiederebbe di mettere le mani su sistemi che nessuno vuole
toccare.

**Una sola chiamata HTTP elimina il problema.** SQUISH espone la stessa identica
pipeline usata sul database — stessi profili, stessi parametri, stessa salvaguardia
dell'originale — come endpoint REST. Nessuna integrazione database, nessun accesso al
codice esistente, nessun agente da installare.

```bash
# Compressione on-demand: il PDF ottimizzato torna nella risposta
curl -X POST https://squish.azienda.it/api/compress \
  -u utente:password \
  -F "file=@contratto.pdf" \
  -o contratto_ottimizzato.pdf
```

Il profilo si sceglie **per singola richiesta**, quindi lo stesso endpoint produce
risultati diversi a seconda di ciò che serve:

```bash
# Documento riservato: watermark diagonale + cifratura AES-256 dell'output
curl -X POST https://squish.azienda.it/api/compress \
  -u utente:password \
  -F "file=@offerta.pdf" \
  -F "profile=confidential" \
  -F "outputPassword=segreto" \
  -o offerta_protetta.pdf

# Archiviazione a norma: conversione PDF/A
curl -X POST https://squish.azienda.it/api/compress \
  -u utente:password \
  -F "file=@delibera.pdf" \
  -F "profile=archival" \
  -o delibera_pdfa.pdf
```

Cosa si ottiene, con la stessa chiamata:

| Capacità | Come |
|----------|------|
| **Compressione** | Profilo `office` / `web`, oppure scala e qualità personalizzate |
| **Watermark** | Profilo con filigrana: 7 posizioni, opacità, font e colore |
| **Conformità PDF/A** | Profilo `archival`: PDF/A-1B, 2B o 3B |
| **Cifratura output** | Parametro `outputPassword`: AES-256 |
| **PDF già protetti** | Parametro `password`: apre il documento e lo rielabora |
| **Originale preservato** | Se non c'è guadagno, il file torna intatto |

Modalità di risposta:

- **Binaria** (predefinita) — il PDF, con gli header `X-Original-Size`,
  `X-Compressed-Size`, `X-Savings-Percent`, `X-Duration-Ms`
- **JSON** (`format=json`) — statistiche e PDF in Base64, per client che vogliono
  loggare o decidere in base al risultato

Sicurezza dell'endpoint: **autenticazione HTTP Basic** obbligatoria sulle operazioni di
scrittura, HTTPS/TLS con keystore PKCS12 o JKS, CORS chiuso per impostazione predefinita
e apribile solo alle origini autorizzate.

---

## Modalità Operative

### Batch Mode (predefinita)
Elabora i record una volta, dal range ID configurato, poi termina. Ideale per:
- Migrazione e ottimizzazione di archivi storici
- Ottimizzazione periodica pianificata
- Progetti di digitalizzazione

### Watchdog Mode
Monitoraggio continuo: interroga periodicamente il database per nuovi record. Ideale per:
- Compressione dei nuovi documenti in ingresso
- Integrazione con i flussi documentali

### REST API
Compressione on-demand via HTTP, senza integrazione database. Ideale per:
- Applicazioni web, portali e microservizi
- Sistemi di terze parti che non si possono modificare
- Documenti che entrano in azienda fuori dai flussi presidiati
- Workflow personalizzati, con il profilo scelto a ogni chiamata

---

## Integrazione e Monitoraggio

### Database
- **Oracle Database** - integrazione nativa: legge i BLOB PDF, li comprime, li riscrive
- **Tabella di tracking `SQUISH_PROCESSED`** - stati `SUCCESS` / `SKIPPED` / `ERROR`
  per evitare il riprocessamento e consentire la ripresa
- **Connection pooling** - HikariCP
- **Validazione PDF** - controllo dei magic bytes (`%PDF-`): i file non-PDF sono
  marcati `SKIPPED` e non alterati

### REST API

```bash
POST /api/compress     # compressione on-demand (auth richiesta)
GET/POST /api/config   # lettura e switch del profilo attivo
GET  /api/profiles     # elenco dei profili configurati
GET  /api/status       # stato della pipeline in tempo reale
GET  /api/health       # health check per load balancer e orchestratori
GET  /metrics          # metriche in formato Prometheus
```

Gli endpoint di scrittura richiedono autenticazione HTTP Basic; quelli di lettura sono
pensati per essere interrogati da sistemi di monitoraggio.

### Dashboard Web
- Stato real-time della pipeline
- Statistiche di compressione (originale vs compresso)
- Gestione e switch dei profili
- UI configurazione su `/config`

### Prometheus Metrics
Metriche per integrazione con Grafana, Datadog e simili:

- `squish_records_compressed_total` - PDF compressi
- `squish_records_skipped_total` - file non-PDF saltati
- `squish_records_failed_total` - errori di compressione
- `squish_bytes_original_total` / `squish_bytes_compressed_total`
- `squish_compression_ratio` - rapporto compresso/originale
- `squish_savings_percent` - risparmio percentuale corrente
- `squish_compression_duration` - istogramma dei tempi

Sono inoltre disponibili report PDF automatici e notifiche email SMTP (STARTTLS/SSL).

---

## Architettura Tecnica

### Stack Tecnologico

| Componente | Tecnologia |
|------------|------------|
| **Runtime** | Java 22+ con Virtual Threads |
| **Framework** | Spring Boot 3.2 |
| **PDF Engine** | iText 8 (kernel, layout, pdfa, bouncy-castle-adapter) |
| **Database** | Oracle con HikariCP |
| **Metriche** | Micrometer + Prometheus |
| **Immagini** | TwelveMonkeys ImageIO |
| **Sicurezza** | Bouncy Castle, TLS, AES-256 |

### Qualità del Software
- **143 test automatici** (JUnit 5 + AssertJ) su compressione, watermark, PDF/A,
  profili e livello HTTP

### Deployment
- JAR eseguibile (Spring Boot)
- Immagine Docker
- Servizio systemd / init script
- HTTPS con keystore PKCS12 o JKS

### Requisiti di Sistema
- Java 22+
- Connettività verso Oracle Database
- Risorse (RAM/CPU) dimensionate sul volume documentale

---

## Casi d'Uso

### Settore Bancario e Finanziario
- Compressione di estratti conto e documentazione cliente scansionata
- Archiviazione PDF/A per conformità
- Watermark automatico su documenti riservati

### Sanità
- Ottimizzazione di referti e documentazione clinica digitalizzata
- Conformità per l'archiviazione a lungo termine

### Pubblica Amministrazione
- Digitalizzazione e ottimizzazione di archivi storici
- Conformità all'archiviazione documentale a norma

### Enterprise Generale
- Ottimizzazione di archivi documentali su database Oracle
- Integrazione con sistemi ECM/DMS

---

## Perché Scegliere SQUISH

- **Tecnologia moderna** - Java 22 Virtual Threads, architettura a pipeline
- **Non distruttivo** - l'originale è preservato quando non c'è guadagno reale
- **Conformità** - PDF/A per archiviazione a lungo termine
- **Enterprise-ready** - Oracle, dashboard, Prometheus, HTTPS
- **API-first** - qualunque applicazione ottiene PDF ottimizzati, sicuri e con
  watermark, senza integrazione database e senza toccare i sistemi esistenti
- **Trasparente** - parametri di compressione espliciti e verificabili
- **Made in Italy** - sviluppo e supporto in italiano

---

## Contatti

**Richiedi una demo personalizzata sui tuoi documenti**

Un assessment sui vostri archivi reali permette di stimare il risparmio effettivo
prima di qualsiasi rollout.

**LUCSARTECH SRL**
Viale Martiri della Libertà 44 — 43036 Fidenza (PR)
P.IVA 03085420341

Referente: Gianluca Simonini
Email: gianluca.simonini@lucsartech.it
Web: www.lucsartech.it

---

*SQUISH 3.0 — Comprimi il presente, archivia il futuro*

© 2026 Lucsartech Srl — Tutti i diritti riservati
