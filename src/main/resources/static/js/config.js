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

document.getElementById('applyBtn').addEventListener('click', applyProfile);
loadConfig();
