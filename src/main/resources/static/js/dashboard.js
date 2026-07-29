// Squish dashboard client. Populates the static shell via /api/status.
// Field names and DOM ids are a public contract shared with the server-side DTOs.

// ---- formatting helpers ---------------------------------------------------
function formatSize(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const k = 1024;
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    const size = bytes / Math.pow(k, i);
    return size.toFixed(i < 2 ? 0 : 1) + ' ' + units[i];
}
// Split a formatted size into { num, unit } so the hero can render the unit
// smaller. Reuses formatSize so rounding stays consistent with the rest.
function splitSize(bytes) {
    const s = formatSize(bytes);
    const sp = s.indexOf(' ');
    return { num: s.slice(0, sp), unit: s.slice(sp + 1) };
}
function toMb(bytes) { return bytes / 1024 / 1024; }
function formatElapsed(seconds) {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = seconds % 60;
    return h > 0
        ? `${h}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`
        : `${m}:${String(s).padStart(2,'0')}`;
}
function setText(id, value) {
    const el = document.getElementById(id);
    if (el) el.textContent = value;
}
function pct(part, whole) {
    return whole > 0 ? (part / whole * 100) : 0;
}

// Build a single activity row using DOM APIs + textContent (no innerHTML).
// `filename` originates from the OTT_NOME_FILE column / user uploads and is
// untrusted, so it is only ever assigned via textContent / .title - never
// interpolated into markup.
function buildActivityEntry(a) {
    const status = String(a.status || '').toLowerCase();

    const entry = document.createElement('div');
    entry.className = 'arow';

    const idSpan = document.createElement('span');
    idSpan.className = 'a-id';
    idSpan.textContent = (a.id === undefined || a.id === null || a.id === -1) ? '—' : a.id;

    const nameSpan = document.createElement('span');
    nameSpan.className = 'a-name';
    nameSpan.title = a.filename || '';
    nameSpan.textContent = a.filename || '-';

    const statusSpan = document.createElement('span');
    statusSpan.className = 'a-stat ' + status;
    statusSpan.textContent = a.status;

    const sizeSpan = document.createElement('span');
    sizeSpan.className = 'a-size';
    if (status === 'compressed' || status === 'stamped' || status === 'watermarked') {
        const o = document.createElement('span'); o.className = 'o'; o.textContent = formatSize(a.originalSize);
        const x = document.createElement('span'); x.className = 'x'; x.textContent = ' → ';
        const c = document.createElement('span'); c.className = 'c'; c.textContent = formatSize(a.compressedSize);
        sizeSpan.append(o, x, c);
    } else {
        const x = document.createElement('span'); x.className = 'x';
        x.textContent = status === 'error' || status === 'failed' ? 'not compressed' : 'already optimal';
        sizeSpan.append(x);
    }

    const savingsSpan = document.createElement('span');
    savingsSpan.className = 'a-save';
    savingsSpan.textContent = (a.savingsPercent > 0) ? a.savingsPercent.toFixed(1) + '%' : '—';

    const timeSpan = document.createElement('span');
    timeSpan.className = 'a-time';
    timeSpan.textContent = (a.timeMs || 0) + 'ms';

    entry.append(idSpan, nameSpan, statusSpan, sizeSpan, savingsSpan, timeSpan);
    return entry;
}

// Compose the on-demand "Last" cell. lastFilename is untrusted (user upload),
// so it goes through textContent, never markup.
function renderOnDemandLast(od) {
    const el = document.getElementById('onDemandLast');
    if (!od || !od.lastFilename) { el.textContent = '—'; return; }
    const name = document.createElement('span');
    name.textContent = od.lastFilename;
    name.title = od.lastFilename;
    el.replaceChildren(name);
    if (typeof od.lastSavingsPercent === 'number') {
        const save = document.createElement('span');
        save.style.color = od.lastSuccess === false ? 'var(--red)' : 'var(--green)';
        save.textContent = ' · −' + Math.abs(od.lastSavingsPercent).toFixed(0) + '%';
        el.appendChild(save);
    }
}

// Watermark / PDF/A rows: a text label followed by an on/off pill.
function renderPill(id, on, label) {
    const el = document.getElementById(id);
    el.replaceChildren();
    if (label) { el.appendChild(document.createTextNode(label + ' ')); }
    const pill = document.createElement('span');
    pill.className = 'pill ' + (on ? 'on' : 'off');
    pill.textContent = on ? 'on' : 'off';
    el.appendChild(pill);
}

async function refresh() {
    try {
        const res = await fetch('/api/status?_=' + Date.now());
        if (!res.ok) return;

        const json = await res.json();
        const d = json.data;

        // ---- header ----
        const liveEl = document.getElementById('liveStatus');
        let liveText = (d.completed ? 'Complete' : 'Running') + ' · ' + (json.watchMode ? 'watch' : 'batch');
        if (json.mode && String(json.mode).toUpperCase().includes('DRY')) liveText += ' · dry-run';
        liveEl.textContent = liveText;
        setText('elapsed', formatElapsed(d.elapsedSeconds));
        if (json.version) {
            // Append the build number so the footer names the exact build, not just the release.
            let footer = 'Squish ' + json.version;
            if (json.buildNumber && json.buildNumber !== 'unknown') footer += ' (' + json.buildNumber + ')';
            setText('footerVersion', footer);
            const fv = document.getElementById('footerVersion');
            if (fv && json.buildTime && json.buildTime !== 'unknown') fv.title = 'Built ' + json.buildTime;
        }

        // ---- 01 space reclaimed ----
        const currentSize = d.completed ? d.finalDbSizeBytes : d.currentDbSizeBytes;
        const reclaimed = d.initialDbSizeBytes - currentSize;
        const smallerPct = pct(reclaimed, d.initialDbSizeBytes);
        const rec = splitSize(Math.max(0, reclaimed));
        setText('reclaimedNum', rec.num);
        setText('reclaimedUnit', rec.unit);
        setText('dbSmallerPct', smallerPct.toFixed(1) + '%');
        setText('heroInitial', formatSize(d.initialDbSizeBytes));
        setText('heroCurrent', formatSize(currentSize));

        // squeeze bars: compressed width relative to original bytes
        setText('origBytes', formatSize(d.originalBytes));
        setText('compBytes', formatSize(d.compressedBytes));
        document.getElementById('compFill').style.width =
            Math.min(100, pct(d.compressedBytes, d.originalBytes)).toFixed(1) + '%';
        setText('compDocCount', d.compressed.toLocaleString());
        setText('avgPerFile', d.savingsPercent.toFixed(1) + '%');

        // ---- 02 honest run progress (segmented over totalRecords) ----
        setText('readCount', d.read.toLocaleString());
        setText('totalCount', d.totalRecords.toLocaleString());
        const total = d.totalRecords || 0;
        document.getElementById('segComp').style.width = pct(d.compressed, total).toFixed(1) + '%';
        document.getElementById('segSkip').style.width = pct(d.skipped, total).toFixed(1) + '%';
        document.getElementById('segFail').style.width = pct(d.errors, total).toFixed(1) + '%';
        setText('legendComp', d.compressed.toLocaleString());
        setText('legendSkip', d.skipped.toLocaleString());
        setText('legendFail', d.errors.toLocaleString());
        const runBadge = document.getElementById('runBadge');
        runBadge.textContent = d.completed ? 'complete' : 'running';
        runBadge.classList.toggle('running', !d.completed);

        // ---- 03 active profile ----
        const p = json.profile;
        if (p) {
            setText('profileName', 'Profile · ' + (p.name || '—'));
            let comp;
            if (p.lossless) {
                comp = (p.mode || 'LOSSLESS');
            } else {
                comp = (p.mode || 'custom')
                    + ' · scale ' + (typeof p.scaleFactor === 'number' ? p.scaleFactor.toFixed(2) : '—')
                    + ' · jpeg ' + (typeof p.jpegQuality === 'number' ? p.jpegQuality.toFixed(2) : '—');
            }
            setText('profileCompression', comp);

            const wmLabel = p.watermarkEnabled
                ? [p.watermarkText, p.watermarkPosition,
                   (typeof p.watermarkOpacity === 'number' ? Math.round(p.watermarkOpacity * 100) + '%' : null)]
                    .filter(Boolean).join(' · ')
                : '';
            renderPill('profileWatermark', !!p.watermarkEnabled, wmLabel);

            const pdfaLabel = (p.pdfaEnabled && p.pdfaConformance)
                ? String(p.pdfaConformance).replace('PDF_A_', 'PDF/A-') : '';
            renderPill('profilePdfa', !!p.pdfaEnabled, pdfaLabel);
        }

        // ---- 03 on-demand REST ----
        const od = json.onDemand;
        if (od) {
            setText('onDemandCalls',
                (od.calls || 0).toLocaleString() + ' · avg ' + Math.round(od.avgMs || 0) + ' ms');
            renderOnDemandLast(od);
        }

        // ---- 04 throughput & health ----
        setText('recordsPerSec', d.recordsPerSecond.toFixed(1));
        setText('mbPerSec', d.mbPerSecond.toFixed(2));
        setText('avgTime', d.avgProcessingTimeMs);
        setText('errors', d.errors.toLocaleString());
        setText('dlq', d.dlqSize);

        const sys = json.system;
        if (sys) {
            setText('cpuCores', sys.cpuCores);
            setText('cpuPercent', sys.cpuPercent.toFixed(0));
            document.getElementById('cpuBar').style.width = Math.min(100, sys.cpuPercent) + '%';
            setText('memoryMax', formatSize(sys.memMax));
            setText('memoryUsed', formatSize(sys.memUsed));
            setText('activeThreads', sys.activeThreads);
            document.getElementById('memoryBar').style.width = Math.min(100, sys.memPercent) + '%';
        }

        // ---- 05 live activity (DOM APIs only; filenames are untrusted) ----
        const activityLog = document.getElementById('activityLog');
        if (json.activity && json.activity.length > 0) {
            activityLog.replaceChildren(...json.activity.map(buildActivityEntry));
        } else {
            const placeholder = document.createElement('div');
            placeholder.className = 'a-empty';
            placeholder.textContent = 'Waiting for activity…';
            activityLog.replaceChildren(placeholder);
        }

    } catch (e) {
        console.error('Refresh error:', e);
    }
}

refresh();
setInterval(refresh, 2000);
