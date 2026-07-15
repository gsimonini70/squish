# Copyright (c) 2026, Lucsartech Srl
# Genera la presentazione commerciale di SQUISH 3.0 in formato .pptx (16:9).
# Look & feel ufficiale Lucsartech (slate: Helvetica Neue + Menlo, header scuro,
# zebra, sezioni numerate in Menlo). I contenuti riflettono SOLO fatti verificati
# nel codice sorgente: percentuali espresse come intervalli dipendenti dal contenuto,
# nessuna cifra di storage inventata.
#
# Riproducibile:  python3 docs/build_presentation.py

import os

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Emu, Inches, Pt

# --- design tokens (dal generatore ufficiale preventivo.py) ------------------
FONT = "Helvetica Neue"          # testo e titoli
MONO = "Menlo"                   # kicker, numeri sezione, codici
INK = "111827"                   # titoli
INK2 = "1F2937"                  # corpo
SUB = "4B5563"
MUTED = "6B7280"
MUTED2 = "9CA3AF"
HEADER_BG = "111827"             # header tabelle (testo bianco)
ZEBRA = "F4F4F7"                 # righe alternate
SOFT = "F9FAFB"
GREEN_TINT = "F0FAF2"
LINE = "D1D5DB"
WHITE = "FFFFFF"

# --- layout: la slide e' 7.5in alta, il footer parte a 7.05in ----------------
FOOTER_TOP_IN = 7.05
TABLE_BOTTOM_GAP_IN = 0.20   # aria fra ultima riga e footer
NOTE_RESERVE_IN = 0.70       # spazio riservato alla nota sotto la tabella
MIN_ROW_H_IN = 0.40          # sotto questa soglia il testo non e' leggibile

LOGO_PATH = os.path.expanduser("~/.claude/skills/lucsartech-preventivo/logo.png")
OUT_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "SQUISH_Presentazione_Commerciale.pptx")

EMU_W = Inches(13.333)
EMU_H = Inches(7.5)


def _rgb(hex6):
    return RGBColor.from_string(hex6)


def _no_autofit(tf):
    # disabilita l'autofit che ridurrebbe il font
    tf.word_wrap = True


def _set_fill(shape, hex6):
    shape.fill.solid()
    shape.fill.fore_color.rgb = _rgb(hex6)
    shape.line.fill.background()


def _txt(shape_or_tf, text, *, size, bold=False, color=INK2, font=FONT,
         align=PP_ALIGN.LEFT, space_after=4, space_before=0, first=False):
    """Aggiunge un paragrafo con run formattato. Se first, riusa il paragrafo 0."""
    tf = shape_or_tf
    p = tf.paragraphs[0] if first and not tf.paragraphs[0].runs else tf.add_paragraph()
    p.alignment = align
    p.space_after = Pt(space_after)
    p.space_before = Pt(space_before)
    r = p.add_run()
    r.text = text
    f = r.font
    f.name = font
    f.size = Pt(size)
    f.bold = bold
    f.color.rgb = _rgb(color)
    return p


def _textbox(slide, left, top, width, height, anchor=MSO_ANCHOR.TOP):
    tb = slide.shapes.add_textbox(left, top, width, height)
    tf = tb.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = 0
    tf.margin_right = 0
    tf.margin_top = 0
    tf.margin_bottom = 0
    return tf


def _rect(slide, left, top, width, height, hex6):
    sp = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, left, top, width, height)
    _set_fill(sp, hex6)
    sp.shadow.inherit = False
    return sp


class Deck:
    def __init__(self):
        self.prs = Presentation()
        self.prs.slide_width = EMU_W
        self.prs.slide_height = EMU_H
        self.blank = self.prs.slide_layouts[6]
        self.n = 0

    def _slide(self, bg=WHITE):
        s = self.prs.slides.add_slide(self.blank)
        _rect(s, 0, 0, EMU_W, EMU_H, bg)
        return s

    def _logo(self, slide, left, top, height):
        if os.path.exists(LOGO_PATH):
            slide.shapes.add_picture(LOGO_PATH, left, top, height=height)

    def _footer(self, slide, dark=False):
        col = MUTED2 if not dark else "6B7280"
        # Stops short of the page-number box at 11.7in.
        tf = _textbox(slide, Inches(0.6), Inches(7.05), Inches(10.9), Inches(0.35))
        _txt(tf, "LUCSARTECH SRL  ·  Fidenza (PR)  ·  SQUISH 3.0", size=8,
             color=col, font=MONO, first=True)
        p = tf.paragraphs[0]
        # numero pagina a destra su stesso box: usiamo un secondo box
        tf2 = _textbox(slide, Inches(11.7), Inches(7.05), Inches(1.0), Inches(0.35))
        _txt(tf2, f"{self.n:02d}", size=8, color=col, font=MONO,
             align=PP_ALIGN.RIGHT, first=True)

    def _header(self, slide, kicker, title):
        # banda numero sezione + kicker + titolo
        self._logo(slide, Inches(11.9), Inches(0.5), Inches(0.55))
        tf = _textbox(slide, Inches(0.7), Inches(0.55), Inches(11.0), Inches(0.35))
        _txt(tf, f"{self.n:02d}  ·  {kicker.upper()}", size=11, bold=True,
             color=MUTED, font=MONO, first=True)
        # Stops short of the logo at 11.9in.
        tt = _textbox(slide, Inches(0.7), Inches(0.92), Inches(10.9), Inches(0.8))
        _txt(tt, title, size=28, bold=True, color=INK, first=True)
        # linea sotto il titolo
        _rect(slide, Inches(0.7), Inches(1.72), Inches(11.93), Pt(2.2), INK)

    # ---- slide types -------------------------------------------------------
    def cover(self):
        self.n += 1
        s = self._slide(bg=HEADER_BG)
        self._logo(s, Inches(0.75), Inches(0.7), Inches(1.15))
        tf = _textbox(s, Inches(0.8), Inches(2.5), Inches(11.7), Inches(3.0))
        _txt(tf, "PRESENTAZIONE COMMERCIALE", size=13, bold=True, color=MUTED2,
             font=MONO, first=True)
        _txt(tf, "SQUISH 3.0", size=60, bold=True, color=WHITE, space_before=6)
        _txt(tf, "Compressione intelligente dei PDF per l'impresa",
             size=22, color="D1D5DB", space_before=8)
        _txt(tf, "Pipeline Java 22 · Integrazione Oracle · PDF/A · Sicurezza AES-256",
             size=13, color=MUTED2, font=MONO, space_before=16)
        # blocco dati azienda in basso
        tf2 = _textbox(s, Inches(0.8), Inches(6.2), Inches(11.7), Inches(1.0))
        _txt(tf2, "LUCSARTECH SRL", size=11, bold=True, color=WHITE,
             font=MONO, first=True)
        _txt(tf2, "Viale Martiri della Libertà 44, 43036 Fidenza (PR)  ·  "
                  "P.IVA 03085420341  ·  gianluca.simonini@lucsartech.it",
             size=10, color=MUTED2, space_before=2)

    def bullets_slide(self, kicker, title, items, intro=None):
        self.n += 1
        s = self._slide()
        self._header(s, kicker, title)
        top = 2.15
        if intro:
            tf = _textbox(s, Inches(0.7), Inches(top), Inches(11.9), Inches(0.6))
            _txt(tf, intro, size=14, color=SUB, first=True)
            top += 0.7
        tf = _textbox(s, Inches(0.7), Inches(top), Inches(11.9),
                      Inches(6.7 - top))
        for i, it in enumerate(items):
            if isinstance(it, tuple):
                head, body = it
                _txt(tf, head, size=16, bold=True, color=INK,
                     space_before=(2 if i == 0 else 12), first=(i == 0))
                _txt(tf, body, size=13, color=INK2, space_after=2, space_before=1)
            else:
                p = _txt(tf, "•  " + it, size=15, color=INK2,
                         space_after=8, space_before=(0 if i == 0 else 2),
                         first=(i == 0))
        self._footer(s)

    def two_col(self, kicker, title, left_head, left_items, right_head, right_items):
        self.n += 1
        s = self._slide()
        self._header(s, kicker, title)
        for x, head, items in ((0.7, left_head, left_items),
                               (6.95, right_head, right_items)):
            # card
            card = _rect(s, Inches(x), Inches(2.2), Inches(5.7), Inches(4.55), SOFT)
            card.line.color.rgb = _rgb(LINE)
            card.line.width = Pt(0.75)
            tf = _textbox(s, Inches(x + 0.3), Inches(2.5), Inches(5.1), Inches(4.0))
            _txt(tf, head.upper(), size=11, bold=True, color=MUTED, font=MONO,
                 first=True)
            for i, it in enumerate(items):
                _txt(tf, "•  " + it, size=13, color=INK2, space_after=6,
                     space_before=(6 if i == 0 else 2))
        self._footer(s)

    def table_slide(self, kicker, title, header, rows, col_w, intro=None,
                    note=None, highlight_last=False):
        self.n += 1
        s = self._slide()
        self._header(s, kicker, title)
        top = 2.1
        if intro:
            tf = _textbox(s, Inches(0.7), Inches(top), Inches(11.9), Inches(0.55))
            _txt(tf, intro, size=13, color=SUB, first=True)
            top += 0.55
        left = 0.7
        total_w = sum(col_w)
        # Fit the table between the intro and the footer. Row height is derived,
        # not fixed: with a fixed 0.62in a 7-row table ran past the slide edge
        # and collided with the footer.
        head_h = 0.52
        reserved = NOTE_RESERVE_IN if note else 0.0
        avail = FOOTER_TOP_IN - TABLE_BOTTOM_GAP_IN - top - reserved - head_h
        row_h = min(0.62, avail / len(rows)) if rows else 0.62
        if row_h < MIN_ROW_H_IN:
            raise ValueError(
                f"table_slide('{title}'): {len(rows)} rows do not fit "
                f"(row height {row_h:.3f}in < {MIN_ROW_H_IN}in). "
                "Split the slide or shorten the table."
            )
        x = left
        for c, (htxt, w) in enumerate(zip(header, col_w)):
            cell = _rect(s, Inches(x), Inches(top), Inches(w), Inches(head_h),
                         HEADER_BG)
            tf = cell.text_frame
            tf.margin_left = Inches(0.12)
            tf.margin_right = Inches(0.08)
            tf.margin_top = Inches(0.02)
            tf.margin_bottom = Inches(0.02)
            tf.vertical_anchor = MSO_ANCHOR.MIDDLE
            _txt(tf, str(htxt).upper(), size=10, bold=True, color=WHITE,
                 font=MONO, first=True)
            x += w
        # body rows
        y = top + head_h
        for ri, row in enumerate(rows):
            rh = row_h
            is_hl = highlight_last and ri == len(rows) - 1
            base = GREEN_TINT if is_hl else (ZEBRA if ri % 2 == 1 else WHITE)
            x = left
            for ci, (val, w) in enumerate(zip(row, col_w)):
                cell = _rect(s, Inches(x), Inches(y), Inches(w), Inches(rh), base)
                cell.line.color.rgb = _rgb(LINE)
                cell.line.width = Pt(0.5)
                tf = cell.text_frame
                tf.word_wrap = True
                tf.margin_left = Inches(0.12)
                tf.margin_right = Inches(0.1)
                tf.margin_top = Inches(0.03)
                tf.margin_bottom = Inches(0.03)
                tf.vertical_anchor = MSO_ANCHOR.MIDDLE
                bold = ci == 0 or is_hl
                col = INK if bold else INK2
                _txt(tf, str(val), size=11, bold=bold, color=col, first=True)
                x += w
            y += rh
        if note:
            tf = _textbox(s, Inches(0.7), Inches(y + 0.15), Inches(11.9),
                          Inches(0.9))
            _txt(tf, note, size=11, color=MUTED, first=True)
        self._footer(s)

    def diagram_slide(self):
        self.n += 1
        s = self._slide()
        self._header(s, "Architettura e performance",
                     "Pipeline su Virtual Threads")
        tf = _textbox(s, Inches(0.7), Inches(2.05), Inches(11.9), Inches(0.5))
        _txt(tf, "Java 22 Virtual Threads (Project Loom): modello produttore / worker / "
                 "writer con code a capacità limitata (backpressure).",
             size=14, color=SUB, first=True)
        # three stage boxes + arrows
        labels = [("PRODUCER", "Lettura BLOB\nda Oracle DB"),
                  ("WORKER POOL", "Compressione PDF\n(thread virtuali)"),
                  ("WRITER POOL", "Riscrittura\nsul database")]
        bw, bh = 3.35, 1.7
        xs = [0.9, 5.0, 9.1]
        y = 2.95
        for (title, body), x in zip(labels, xs):
            box = _rect(s, Inches(x), Inches(y), Inches(bw), Inches(bh), SOFT)
            box.line.color.rgb = _rgb(INK)
            box.line.width = Pt(1.5)
            tf = box.text_frame
            tf.vertical_anchor = MSO_ANCHOR.MIDDLE
            tf.word_wrap = True
            _txt(tf, title, size=13, bold=True, color=INK, font=MONO,
                 align=PP_ALIGN.CENTER, first=True)
            for line in body.split("\n"):
                _txt(tf, line, size=12, color=INK2, align=PP_ALIGN.CENTER,
                     space_before=2)
        for ax in (4.45, 8.55):
            ar = s.shapes.add_shape(MSO_SHAPE.RIGHT_ARROW, Inches(ax),
                                    Inches(y + 0.62), Inches(0.5), Inches(0.45))
            _set_fill(ar, INK)
        # code line
        code = _rect(s, Inches(0.9), Inches(5.0), Inches(11.55), Inches(0.55), INK)
        tf = code.text_frame
        tf.vertical_anchor = MSO_ANCHOR.MIDDLE
        tf.margin_left = Inches(0.2)
        _txt(tf, "BlockingQueue<PdfTask>  →  Semaphore(worker)  →  "
                 "BlockingQueue<CompressionResult>",
             size=12, color=WHITE, font=MONO, first=True)
        # notes
        tf = _textbox(s, Inches(0.9), Inches(5.85), Inches(11.55), Inches(1.0))
        _txt(tf, "Numero di worker configurabile · elaborazione I/O-bound efficiente · "
                 "il throughput reale dipende da dimensione documenti, latenza DB e "
                 "risorse server e va misurato nell'ambiente del cliente.",
             size=12, color=MUTED, first=True)
        self._footer(s)

    def contact_slide(self):
        self.n += 1
        s = self._slide(bg=HEADER_BG)
        self._logo(s, Inches(0.75), Inches(0.7), Inches(0.95))
        tf = _textbox(s, Inches(0.8), Inches(2.2), Inches(11.7), Inches(2.8))
        _txt(tf, "CONTATTI", size=13, bold=True, color=MUTED2, font=MONO,
             first=True)
        _txt(tf, "Richiedi una demo sui tuoi documenti", size=40, bold=True,
             color=WHITE, space_before=6)
        _txt(tf, "Un assessment sui vostri archivi reali stima il risparmio "
                 "effettivo prima di qualsiasi rollout.",
             size=16, color="D1D5DB", space_before=10)
        tf2 = _textbox(s, Inches(0.8), Inches(5.1), Inches(11.7), Inches(1.8))
        _txt(tf2, "LUCSARTECH SRL", size=15, bold=True, color=WHITE, first=True)
        _txt(tf2, "Viale Martiri della Libertà 44 — 43036 Fidenza (PR)  ·  "
                  "P.IVA 03085420341", size=12, color=MUTED2, space_before=3)
        _txt(tf2, "Referente: Gianluca Simonini", size=13, bold=True,
             color=WHITE, space_before=8)
        _txt(tf2, "gianluca.simonini@lucsartech.it  ·  www.lucsartech.it",
             size=13, color="D1D5DB", font=MONO, space_before=2)

    def save(self):
        self.prs.save(OUT_PATH)
        return OUT_PATH


def build():
    d = Deck()

    # 01 Copertina
    d.cover()

    # 02 Il problema
    d.bullets_slide(
        "Il problema", "Archivi PDF che pesano",
        intro="Le organizzazioni con grandi archivi documentali affrontano volumi "
              "crescenti di file PDF non ottimizzati.",
        items=[
            "Archivi documentali in crescita continua",
            "Costi di storage in aumento, on-premise e in cloud",
            "Tempi di trasferimento e backup elevati per documenti pesanti",
            "Conformità PDF/A sempre più richiesta per l'archiviazione a lungo termine",
            "Gestione manuale inefficiente e soggetta a errori",
            "Molti documenti sono scansioni ricche di immagini: è lì il potenziale di risparmio",
        ])

    # 03 La soluzione
    d.bullets_slide(
        "La soluzione", "SQUISH in sintesi",
        intro="Piattaforma enterprise per la compressione automatizzata dei PDF, "
              "integrata nativamente con Oracle Database e non distruttiva.",
        items=[
            ("Automazione completa",
             "Elaborazione massiva degli archivi senza intervento manuale."),
            ("Nessuna perdita involontaria",
             "Se la compressione non riduce il file, l'originale viene preservato."),
            ("Parametri trasparenti",
             "Scala immagine e qualità JPEG esplicite e verificabili per ogni modalità."),
            ("Conformità e sicurezza",
             "PDF/A per l'archiviazione, lettura e ri-cifratura AES-256 dei PDF protetti."),
        ])

    # 04 Architettura e performance
    d.diagram_slide()

    # 05 Profili di compressione (tabella)
    d.table_slide(
        "Profili di compressione", "Modalità con parametri espliciti",
        header=["Modalità", "Scala img.", "Qualità JPEG", "Scopo"],
        rows=[
            ["LOSSLESS", "1.0 (100%)", "1.0 (100%)",
             "Integrità e conformità: sola ottimizzazione struttura, risparmio marginale"],
            ["MEDIUM", "0.75 (75%)", "0.70 (70%)",
             "Documenti d'ufficio: equilibrio tra dimensione e qualità"],
            ["AGGRESSIVE", "0.50 (50%)", "0.30 (30%)",
             "Web/email: massima riduzione, calo di qualità percepibile"],
            ["CUSTOM", "config.", "config.",
             "Scala e qualità impostate esplicitamente per esigenze specifiche"],
        ],
        col_w=[1.9, 1.55, 1.75, 6.73],
        intro="La compressione ricomprime le immagini raster; sui PDF di solo testo "
              "ottimizza solo la struttura. Profili preconfigurati: archival, office, web, custom.",
        note="Risparmio dipendente dal contenuto. Misure su file campione (piccoli, non "
             "benchmark di produzione): immagine RGB ~23%, scala di grigi ~21%, solo "
             "testo 0% (originale preservato). Su scansioni reali il guadagno è maggiore.")

    # 06 PDF/A e sicurezza
    d.two_col(
        "Conformità e sicurezza", "PDF/A e protezione",
        "Conformità PDF/A",
        ["PDF/A-1B — conformità base, massima compatibilità",
         "PDF/A-2B — ISO 32000-1, JPEG2000 (consigliato)",
         "PDF/A-3B — permette allegati incorporati",
         "Profilo colore sRGB ICC incorporato",
         "Metadati XMP e info documento",
         "Compatibile con compressione e watermark"],
        "Sicurezza",
        ["Lettura di PDF protetti da password",
         "Ri-cifratura dell'output in AES-256",
         "Credenziali via variabili d'ambiente (non in ps aux)",
         "HTTPS/TLS con keystore PKCS12 o JKS",
         "Watermark: 7 posizioni, opacità, font e colore",
         "Validazione magic bytes: i non-PDF non vengono alterati"])

    # 07 Integrazione e monitoraggio
    d.two_col(
        "Integrazione e monitoraggio", "Nativo su Oracle, osservabile",
        "Database & integrazione",
        ["Oracle: legge i BLOB PDF, comprime, riscrive",
         "Tracking SQUISH_PROCESSED: SUCCESS/SKIPPED/ERROR",
         "Evita il riprocessamento, consente la ripresa",
         "Connection pooling HikariCP",
         "REST API: compressione on-demand via /api/compress",
         "Config e profili via /api/config, /api/profiles"],
        "Monitoraggio",
        ["Dashboard web real-time della pipeline",
         "Metriche Prometheus (records_compressed, savings_percent…)",
         "Integrazione Grafana / Datadog",
         "Report PDF automatici",
         "Notifiche email SMTP (STARTTLS/SSL)",
         "143 test automatici (JUnit 5 + AssertJ)"])

    # 08 Modalità operative
    d.bullets_slide(
        "Modalità operative", "Batch, Watchdog e API",
        items=[
            ("Batch Mode (predefinita)",
             "Elabora i record del range configurato una volta, poi termina. "
             "Ideale per migrazione archivi, ottimizzazioni pianificate, digitalizzazione."),
            ("Watchdog Mode",
             "Interroga periodicamente il database per i nuovi record. "
             "Ideale per comprimere i documenti in ingresso e integrarsi nei flussi."),
            ("REST API",
             "Compressione on-demand via HTTP senza integrazione database. "
             "Ideale per applicazioni web, microservizi e workflow personalizzati."),
        ])

    # 09 REST API
    d.bullets_slide(
        "REST API", "Ottimizzare anche dove non era previsto",
        intro="Non tutti i PDF nascono nel gestionale: arrivano da portali, applicativi "
              "di terze parti, caricamenti manuali. Una chiamata HTTP li porta dentro "
              "lo stesso processo, senza toccare i sistemi esistenti.",
        items=[
            ("Una chiamata, la pipeline completa",
             "POST /api/compress restituisce il PDF ottimizzato. Stessi profili e stesse "
             "garanzie della modalità database: se non c'è guadagno, l'originale torna intatto."),
            ("Il profilo si sceglie a ogni richiesta",
             "profile=confidential applica la filigrana · profile=archival converte in PDF/A · "
             "outputPassword cifra l'uscita in AES-256 · password apre i PDF già protetti."),
            ("Integrazione senza attriti",
             "Nessun accesso al database, nessun agente da installare, nessuna modifica al "
             "codice del cliente. Risposta binaria o JSON con le statistiche di compressione."),
            ("Endpoint protetto",
             "Autenticazione HTTP Basic sulle scritture, HTTPS/TLS con keystore PKCS12 o JKS, "
             "CORS chiuso di default e apribile solo alle origini autorizzate."),
        ])

    # 10 Stack tecnologico (tabella)
    d.table_slide(
        "Architettura tecnica", "Stack e deployment",
        header=["Componente", "Tecnologia"],
        rows=[
            ["Runtime", "Java 22+ con Virtual Threads (Project Loom)"],
            ["Framework", "Spring Boot 3.2"],
            ["PDF Engine", "iText 8 (kernel, layout, pdfa, bouncy-castle)"],
            ["Database", "Oracle con HikariCP"],
            ["Metriche", "Micrometer + Prometheus"],
            ["Immagini", "TwelveMonkeys ImageIO"],
            ["Deployment", "JAR eseguibile · Docker · systemd/init · HTTPS"],
        ],
        col_w=[3.0, 8.93],
        intro="Stack moderno, enterprise-ready, con qualità presidiata da 143 test automatici.")

    # 11 Perché Lucsartech
    d.bullets_slide(
        "Perché Lucsartech", "I motivi per scegliere SQUISH",
        items=[
            "Tecnologia moderna — Java 22 Virtual Threads, architettura a pipeline",
            "Non distruttivo — l'originale è preservato quando non c'è guadagno reale",
            "Conformità — PDF/A per l'archiviazione a lungo termine",
            "API-first — PDF ottimizzati, sicuri e con watermark anche fuori dai flussi presidiati",
            "Enterprise-ready — Oracle, dashboard, Prometheus, HTTPS",
            "Trasparente — parametri di compressione espliciti e verificabili",
            "Made in Italy — sviluppo e supporto in italiano",
        ])

    # 12 Contatti
    d.contact_slide()

    path = d.save()
    return path


if __name__ == "__main__":
    out = build()
    # verifica: riapri e stampa slide + titoli
    prs = Presentation(out)
    print(f"OK: {out}")
    print(f"Slide totali: {len(prs.slides.__iter__.__self__._sldIdLst)}")
    for i, sl in enumerate(prs.slides, 1):
        texts = []
        for sh in sl.shapes:
            if sh.has_text_frame and sh.text_frame.text.strip():
                texts.append(sh.text_frame.text.strip().split("\n")[0])
        # il titolo è tipicamente il testo grande dopo il kicker
        title = " | ".join(texts[:3])
        print(f"  {i:02d}. {title[:90]}")
