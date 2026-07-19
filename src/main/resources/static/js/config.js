// Squish configuration UI client. Reads /api/config and switches the active
// compression profile via an authenticated POST. Field names and DOM ids
// are a public contract shared with the server-side DTOs.

let selectedProfile = null;
let currentConfig = null;

function showMessage(text, type) {
    const msg = document.getElementById('message');
    msg.textContent = text;
    msg.className = 'message ' + type;
    setTimeout(() => { msg.className = 'message'; }, 5000);
}

// Create a small badge span with constant styling and text-only content.
function makeBadge(cls, text) {
    const span = document.createElement('span');
    span.className = cls;
    span.textContent = text;
    return span;
}

// Highlight the card matching `name` (dataset compare avoids selector injection).
function highlightSelected(name) {
    document.querySelectorAll('.profile-card').forEach(c => {
        c.classList.toggle('selected', c.dataset.profile === name);
    });
}

// Build one profile card entirely with DOM APIs (no innerHTML, no inline
// handlers) so profile names/descriptions coming from the JSON config can
// never be interpreted as markup.
function buildProfileCard(name, p) {
    const isActive = name === currentConfig.activeProfile;

    const card = document.createElement('div');
    card.className = 'profile-card' + (isActive ? ' active' : '');
    card.dataset.profile = name;
    card.addEventListener('click', () => selectProfile(name));

    const nameRow = document.createElement('div');
    nameRow.className = 'profile-name';
    const nameSpan = document.createElement('span');
    nameSpan.textContent = name;
    nameRow.appendChild(nameSpan);
    if (isActive) {
        const indicator = document.createElement('span');
        indicator.className = 'active-indicator';
        nameRow.appendChild(indicator);
    }

    const desc = document.createElement('div');
    desc.className = 'profile-description';
    desc.textContent = p.description || 'No description';

    const details = document.createElement('div');
    details.className = 'profile-details';

    if (p.mode) {
        const modeClass = 'detail-tag mode-' + String(p.mode).toLowerCase();
        details.appendChild(makeBadge(modeClass, p.mode));
    }
    if (p.customScaleFactor) {
        details.appendChild(makeBadge('detail-tag', 'Scale: ' + (p.customScaleFactor * 100).toFixed(0) + '%'));
    }
    if (p.customJpegQuality) {
        details.appendChild(makeBadge('detail-tag', 'Quality: ' + (p.customJpegQuality * 100).toFixed(0) + '%'));
    }
    if (p.watermarkEnabled) {
        details.appendChild(makeBadge('detail-tag watermark', 'Watermark'));
    }
    if (p.pdfaEnabled) {
        const label = 'PDF/A' + (p.pdfaConformance ? ' ' + p.pdfaConformance.replace('PDF_A_', '') : '');
        details.appendChild(makeBadge('detail-tag pdfa', label));
    }

    card.append(nameRow, desc, details);
    return card;
}

function renderProfiles() {
    const container = document.getElementById('profilesContainer');
    if (!currentConfig || !currentConfig.profiles || Object.keys(currentConfig.profiles).length === 0) {
        container.className = 'loading';
        const empty = document.createElement('div');
        empty.className = 'loading';
        empty.textContent = 'No profiles configured';
        container.replaceChildren(empty);
        return;
    }

    const profiles = Object.entries(currentConfig.profiles);
    container.className = 'profiles-grid';
    const cards = profiles.map(([name, p]) => buildProfileCard(name, p));
    container.replaceChildren(...cards);

    // Re-select previously selected profile
    if (selectedProfile) {
        highlightSelected(selectedProfile);
    }
}

function selectProfile(name) {
    selectedProfile = name;
    highlightSelected(name);

    const btn = document.getElementById('applyBtn');
    btn.disabled = (name === currentConfig.activeProfile);
    btn.textContent = name === currentConfig.activeProfile
        ? 'Already Active'
        : `Apply "${name}" Profile`;
}

async function loadConfig() {
    try {
        const res = await fetch('/api/config');
        currentConfig = await res.json();

        document.getElementById('activeProfile').textContent = currentConfig.activeProfile || 'default';
        document.getElementById('legacyMode').textContent = currentConfig.legacyMode || '-';

        const dryRunEl = document.getElementById('dryRun');
        dryRunEl.replaceChildren(currentConfig.dryRun
            ? makeBadge('badge badge-orange', 'Yes')
            : makeBadge('badge badge-green', 'No'));

        const watchdogEl = document.getElementById('watchdog');
        watchdogEl.replaceChildren(currentConfig.watchdogEnabled
            ? makeBadge('badge badge-blue', 'Enabled')
            : makeBadge('badge', 'Disabled'));

        selectedProfile = currentConfig.activeProfile;
        renderProfiles();

    } catch (e) {
        console.error('Failed to load config:', e);
        const container = document.getElementById('profilesContainer');
        const err = document.createElement('div');
        err.className = 'loading';
        err.style.color = 'var(--accent-red)';
        err.textContent = 'Failed to load configuration';
        container.replaceChildren(err);
    }
}

async function applyProfile() {
    if (!selectedProfile || selectedProfile === currentConfig.activeProfile) return;

    const btn = document.getElementById('applyBtn');
    btn.disabled = true;
    btn.textContent = 'Applying...';

    try {
        const res = await fetch('/api/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify({ activeProfile: selectedProfile })
        });

        // Write endpoints are protected by HTTP Basic auth. Surface auth
        // failures clearly instead of trying to parse a non-JSON body.
        if (res.status === 401 || res.status === 403) {
            showMessage('Not authorized to change configuration. Please sign in with an account that has write access.', 'error');
            btn.disabled = false;
            btn.textContent = 'Apply Selected Profile';
            return;
        }

        const result = await res.json();

        if (result.success) {
            showMessage(result.message, 'success');
            await loadConfig();
        } else {
            showMessage(result.message || 'Failed to apply profile', 'error');
        }
    } catch (e) {
        showMessage('Error: ' + e.message, 'error');
    }

    btn.disabled = false;
    btn.textContent = 'Apply Selected Profile';
}

// ---- execution scope override (watchdog) ---------------------------------
// Field names / ids are a public contract shared with ScopeController + ScopeResponse.

let scopeState = null;

// Populate the doc-type <select> with a "keep configured filter" default plus the allow-listed
// types. Built with DOM APIs / textContent so a doc-type value can never be interpreted as markup.
function renderDocTypeOptions(allowed) {
    const sel = document.getElementById('scopeDocType');
    const field = document.getElementById('scopeDocTypeField');
    if (!sel) return;
    sel.replaceChildren();

    const keep = document.createElement('option');
    keep.value = '';
    keep.textContent = '— keep configured filter —';
    sel.appendChild(keep);

    (allowed || []).forEach(dt => {
        const opt = document.createElement('option');
        opt.value = dt;
        opt.textContent = dt;
        sel.appendChild(opt);
    });

    // Hide the selector entirely when no allow-list is configured: only the id range is overridable.
    if (field) field.style.display = (allowed && allowed.length > 0) ? '' : 'none';
}

// Human-readable summary of the current scope for the status row.
function describeScope(s) {
    if (!s.active) return 'Configured (no override)';
    const to = s.idTo && s.idTo > 0 ? s.idTo : '∞';
    const parts = ['id ' + (s.idFrom || 0) + '–' + to];
    if (s.docType) parts.push('type ' + s.docType);
    if (s.autoRevert) parts.push('auto-revert');
    let txt = 'Override · ' + parts.join(' · ');
    if (s.appliedBy) txt += ' (by ' + s.appliedBy + ')';
    return txt;
}

async function loadScope() {
    const panel = document.getElementById('scopePanel');
    const disabled = document.getElementById('scopeDisabled');
    if (!panel) return;
    try {
        const res = await fetch('/api/scope?_=' + Date.now());
        if (!res.ok) return;
        const s = await res.json();
        scopeState = s;

        // Scope override only affects watchdog mode; be honest about it in batch mode.
        panel.style.display = s.watchdogEnabled ? '' : 'none';
        disabled.style.display = s.watchdogEnabled ? 'none' : 'block';

        setText('scopePoll', s.pollIntervalSeconds != null ? s.pollIntervalSeconds : '?');
        document.getElementById('scopeStatus').textContent = describeScope(s);
        document.getElementById('scopeFilter').textContent = s.effectiveFilter || '—';

        renderDocTypeOptions(s.allowedDocTypes);

        // Reflect the live override into the form so "current" and the inputs agree.
        if (s.active) {
            document.getElementById('scopeIdFrom').value = s.idFrom || 0;
            document.getElementById('scopeIdTo').value = s.idTo || 0;
            document.getElementById('scopeDocType').value = s.docType || '';
            document.getElementById('scopeAutoRevert').checked = !!s.autoRevert;
        }
        document.getElementById('scopeClearBtn').disabled = !s.active;
    } catch (e) {
        console.error('Failed to load scope:', e);
    }
}

// setText mirror (dashboard.js has one; config.js does not) - id + text, null-safe.
function setText(id, value) {
    const el = document.getElementById(id);
    if (el) el.textContent = value;
}

function handleAuthOr(res) {
    if (res.status === 401 || res.status === 403) {
        showMessage('Not authorized to change the execution scope. Sign in with a write-access account.', 'error');
        return true;
    }
    return false;
}

async function applyScope() {
    const btn = document.getElementById('scopeApplyBtn');
    const idFrom = parseInt(document.getElementById('scopeIdFrom').value, 10) || 0;
    const idToRaw = parseInt(document.getElementById('scopeIdTo').value, 10);
    const idTo = isNaN(idToRaw) ? 0 : idToRaw;
    const docType = document.getElementById('scopeDocType').value || null;
    const autoRevert = document.getElementById('scopeAutoRevert').checked;

    if (idTo > 0 && idTo < idFrom) {
        showMessage('Id to must be greater than or equal to Id from (or 0 for no limit).', 'error');
        return;
    }

    btn.disabled = true;
    btn.textContent = 'Applying...';
    try {
        const res = await fetch('/api/scope', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify({ active: true, idFrom, idTo, docType, autoRevert })
        });
        if (!handleAuthOr(res)) {
            const result = await res.json();
            showMessage(result.message || (res.ok ? 'Scope applied' : 'Failed to apply scope'),
                res.ok && result.success ? 'success' : 'error');
            if (res.ok && result.success) await loadScope();
        }
    } catch (e) {
        showMessage('Error: ' + e.message, 'error');
    }
    btn.disabled = false;
    btn.textContent = 'Apply scope';
}

async function clearScope() {
    const btn = document.getElementById('scopeClearBtn');
    btn.disabled = true;
    try {
        const res = await fetch('/api/scope', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify({ active: false })
        });
        if (!handleAuthOr(res)) {
            const result = await res.json();
            showMessage(result.message || 'Scope reset', res.ok && result.success ? 'success' : 'error');
            if (res.ok && result.success) await loadScope();
        }
    } catch (e) {
        showMessage('Error: ' + e.message, 'error');
    }
    // loadScope() re-derives the disabled state; re-enable defensively if the request failed.
    if (scopeState && scopeState.active) btn.disabled = false;
}

const scopeApplyBtn = document.getElementById('scopeApplyBtn');
if (scopeApplyBtn) scopeApplyBtn.addEventListener('click', applyScope);
const scopeClearBtn = document.getElementById('scopeClearBtn');
if (scopeClearBtn) scopeClearBtn.addEventListener('click', clearScope);

document.getElementById('applyBtn').addEventListener('click', applyProfile);
loadConfig();
loadScope();
