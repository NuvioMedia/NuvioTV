package com.nuvio.tv.core.server

object AddonWebPage {

    fun getHtml(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>NuvioTV - Manage Addons</title>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800;900&display=swap" rel="stylesheet">
<style>
  * {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
    -webkit-tap-highlight-color: transparent;
  }
  *:focus, *:active { outline: none !important; }
  body {
    font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: #000;
    color: #fff;
    min-height: 100vh;
    line-height: 1.5;
  }
  .page {
    max-width: 600px;
    margin: 0 auto;
    padding: 0 1.5rem 6rem;
  }
  .header {
    text-align: center;
    padding: 3rem 0 2.5rem;
    border-bottom: 1px solid rgba(255, 255, 255, 0.05);
    margin-bottom: 2.5rem;
  }
  .header-logo {
    height: 40px;
    width: auto;
    margin-bottom: 0.5rem;
    filter: brightness(0) invert(1);
    opacity: 0.9;
  }
  .header p {
    font-size: 0.875rem;
    font-weight: 300;
    color: rgba(255, 255, 255, 0.4);
    letter-spacing: 0.02em;
  }
  .add-section {
    margin-bottom: 2.5rem;
  }
  .add-section label {
    display: block;
    font-size: 0.75rem;
    font-weight: 500;
    color: rgba(255, 255, 255, 0.3);
    letter-spacing: 0.1em;
    text-transform: uppercase;
    margin-bottom: 0.75rem;
  }
  .add-row {
    display: flex;
    gap: 0.75rem;
  }
  .add-row input {
    flex: 1;
    background: transparent;
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 100px;
    padding: 0.875rem 1.25rem;
    color: #fff;
    font-family: inherit;
    font-size: 0.9rem;
    font-weight: 400;
    transition: border-color 0.3s ease;
  }
  .add-row input:focus {
    border-color: rgba(255, 255, 255, 0.4);
  }
  .add-row input::placeholder {
    color: rgba(255, 255, 255, 0.2);
  }
  .add-error {
    color: rgba(207, 102, 121, 0.9);
    font-size: 0.8rem;
    margin-top: 0.75rem;
    display: none;
    padding-left: 1.25rem;
  }
  .btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.5rem;
    background: transparent;
    border: 1px solid rgba(255, 255, 255, 0.2);
    border-radius: 100px;
    padding: 0.875rem 1.5rem;
    color: #fff;
    font-family: inherit;
    font-size: 0.875rem;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.3s ease;
    white-space: nowrap;
    -webkit-tap-highlight-color: transparent;
  }
  .btn:hover {
    background: #fff;
    color: #000;
    border-color: #fff;
  }
  .btn:active { transform: scale(0.97); }
  .btn-save {
    width: 100%;
    padding: 1rem;
    font-size: 0.95rem;
    font-weight: 600;
    margin-top: 2rem;
  }
  .btn-save:disabled {
    opacity: 0.2;
    cursor: not-allowed;
    pointer-events: none;
  }
  .btn-remove {
    border-color: rgba(207, 102, 121, 0.3);
    color: rgba(207, 102, 121, 0.8);
    padding: 0.5rem 1rem;
    font-size: 0.75rem;
  }
  .btn-remove:hover {
    background: rgba(207, 102, 121, 0.15);
    border-color: rgba(207, 102, 121, 0.5);
    color: #CF6679;
  }
  .btn-toggle {
    padding: 0.5rem 1rem;
    font-size: 0.75rem;
    border-color: rgba(255, 255, 255, 0.2);
    color: rgba(255, 255, 255, 0.85);
  }
  .btn-toggle:hover {
    background: rgba(255, 255, 255, 0.08);
    color: #fff;
    border-color: rgba(255, 255, 255, 0.35);
  }
  .btn-toggle.disabled {
    border-color: rgba(207, 102, 121, 0.35);
    color: rgba(207, 102, 121, 0.95);
  }
  .btn-toggle.disabled:hover {
    background: rgba(207, 102, 121, 0.15);
    border-color: rgba(207, 102, 121, 0.55);
    color: #CF6679;
  }
  .section-label {
    font-size: 0.75rem;
    font-weight: 500;
    color: rgba(255, 255, 255, 0.3);
    letter-spacing: 0.1em;
    text-transform: uppercase;
    margin-bottom: 1rem;
  }
  .addon-list {
    list-style: none;
  }
  .addon-item {
    border-top: 1px solid rgba(255, 255, 255, 0.06);
    padding: 1rem 0;
    display: flex;
    align-items: center;
    gap: 0.75rem;
  }
  .addon-item:last-child {
    border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  }
  .addon-order {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    flex-shrink: 0;
  }
  .btn-order {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    background: transparent;
    border: 1px solid rgba(255, 255, 255, 0.1);
    border-radius: 6px;
    color: rgba(255, 255, 255, 0.4);
    font-size: 0.7rem;
    cursor: pointer;
    transition: all 0.2s ease;
    padding: 0;
    -webkit-tap-highlight-color: transparent;
  }
  .btn-order:hover {
    background: rgba(255, 255, 255, 0.08);
    border-color: rgba(255, 255, 255, 0.25);
    color: #fff;
  }
  .btn-order:active {
    transform: scale(0.9);
  }
  .btn-order:disabled {
    opacity: 0.15;
    cursor: not-allowed;
    pointer-events: none;
  }
  .addon-info {
    flex: 1;
    min-width: 0;
  }
  .addon-name {
    font-size: 0.95rem;
    font-weight: 600;
    letter-spacing: -0.01em;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .addon-url {
    font-size: 0.75rem;
    color: rgba(255, 255, 255, 0.25);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    margin-top: 0.15rem;
  }
  .addon-desc {
    font-size: 0.8rem;
    color: rgba(255, 255, 255, 0.4);
    margin-top: 0.15rem;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .addon-actions {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }
  .badge-new {
    display: inline-block;
    font-size: 0.6rem;
    font-weight: 700;
    letter-spacing: 0.05em;
    text-transform: uppercase;
    color: #000;
    background: #fff;
    padding: 0.15rem 0.4rem;
    border-radius: 100px;
    margin-left: 0.5rem;
    vertical-align: middle;
  }
  .empty-state {
    text-align: center;
    color: rgba(255, 255, 255, 0.2);
    padding: 3rem 0;
    font-size: 0.875rem;
    font-weight: 300;
    display: none;
  }
  .section-block {
    margin-top: 2rem;
  }
  .catalog-info {
    flex: 1;
    min-width: 0;
  }
  .catalog-name {
    font-size: 0.95rem;
    font-weight: 600;
    letter-spacing: -0.01em;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .catalog-meta {
    font-size: 0.75rem;
    color: rgba(255, 255, 255, 0.35);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    margin-top: 0.15rem;
  }
  .badge-disabled {
    display: inline-block;
    font-size: 0.6rem;
    font-weight: 700;
    letter-spacing: 0.05em;
    text-transform: uppercase;
    color: rgba(207, 102, 121, 0.95);
    border: 1px solid rgba(207, 102, 121, 0.35);
    padding: 0.12rem 0.45rem;
    border-radius: 100px;
    margin-left: 0.5rem;
    vertical-align: middle;
  }
  .status-overlay {
    position: fixed;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    background: rgba(0, 0, 0, 0.92);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    z-index: 500;
    display: flex;
    align-items: center;
    justify-content: center;
    opacity: 0;
    visibility: hidden;
    transition: all 0.3s ease;
  }
  .status-overlay.visible {
    opacity: 1;
    visibility: visible;
  }
  .status-content {
    text-align: center;
    max-width: 340px;
    padding: 2rem;
  }
  .status-icon {
    margin-bottom: 1.5rem;
  }
  .spinner {
    width: 40px;
    height: 40px;
    border: 2px solid rgba(255, 255, 255, 0.1);
    border-top-color: #fff;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
    margin: 0 auto;
  }
  @keyframes spin { to { transform: rotate(360deg); } }
  .status-title {
    font-size: 1.25rem;
    font-weight: 700;
    letter-spacing: -0.02em;
    margin-bottom: 0.5rem;
  }
  .status-message {
    font-size: 0.875rem;
    font-weight: 300;
    color: rgba(255, 255, 255, 0.4);
    line-height: 1.6;
  }
  .status-success .status-title { color: #fff; }
  .status-rejected .status-title { color: rgba(207, 102, 121, 0.9); }
  .status-error .status-title { color: rgba(207, 102, 121, 0.9); }
  .status-dismiss {
    margin-top: 1.5rem;
  }
  .status-svg {
    width: 40px;
    height: 40px;
    margin: 0 auto;
  }
  .status-svg svg {
    width: 40px;
    height: 40px;
  }
  .connection-bar {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    background: rgba(207, 102, 121, 0.15);
    border-bottom: 1px solid rgba(207, 102, 121, 0.3);
    padding: 0.75rem 1.5rem;
    text-align: center;
    font-size: 0.8rem;
    font-weight: 500;
    color: rgba(207, 102, 121, 0.9);
    z-index: 600;
    display: none;
  }
  .connection-bar.visible {
    display: block;
  }
  @media (max-width: 480px) {
    .page { padding: 0 1rem 5rem; }
    .header { padding: 2rem 0 2rem; }
    .header-logo { height: 32px; }
  }
</style>
</head>
<body>
<div class="page">
  <div class="header">
    <img src="/logo.png" alt="NuvioTV" class="header-logo">
    <p>Manage addons and home catalogs</p>
  </div>

  <div class="add-section">
    <label>Add addon by URL</label>
    <div class="add-row">
      <input type="url" id="addonUrl" placeholder="https://example.com/manifest.json" autocomplete="off" autocapitalize="off" spellcheck="false">
      <button class="btn" id="addBtn" onclick="addAddon()">Add</button>
    </div>
    <div class="add-error" id="addError"></div>
  </div>

  <div class="section-label">Installed Addons</div>
  <ul class="addon-list" id="addonList"></ul>
  <div class="empty-state" id="emptyState">No addons installed</div>

  <div class="section-block">
    <div class="section-label">Home Catalogs</div>
    <ul class="addon-list" id="catalogList"></ul>
    <div class="empty-state" id="catalogEmptyState">No home catalogs available</div>
  </div>

  <button class="btn btn-save" id="saveBtn" onclick="saveChanges()">Save Changes</button>
</div>

<div class="status-overlay" id="statusOverlay">
  <div class="status-content" id="statusContent"></div>
</div>

<div class="connection-bar" id="connectionBar">Connection to TV lost</div>

<script>
var addons = [];
var originalAddons = [];
var catalogs = [];
var originalCatalogs = [];
var pollTimer = null;
var pollStartTime = 0;
var POLL_TIMEOUT = 120000;
var POLL_INTERVAL = 1500;
var connectionLost = false;
var consecutiveErrors = 0;

async function fetchWithTimeout(url, options, timeoutMs) {
  var controller = new AbortController();
  var timer = setTimeout(function() { controller.abort(); }, timeoutMs);
  try {
    var opts = options || {};
    opts.signal = controller.signal;
    return await fetch(url, opts);
  } finally {
    clearTimeout(timer);
  }
}

async function loadState() {
  try {
    var res = await fetchWithTimeout('/api/state', {}, 5000);
    var state = await res.json();
    addons = state.addons || [];
    catalogs = state.catalogs || [];
    originalAddons = JSON.parse(JSON.stringify(addons));
    originalCatalogs = JSON.parse(JSON.stringify(catalogs));
    setConnectionLost(false);
    renderAddons();
    renderCatalogs();
  } catch (e) {
    setConnectionLost(true);
  }
}

function setConnectionLost(lost) {
  connectionLost = lost;
  document.getElementById('connectionBar').className = 'connection-bar' + (lost ? ' visible' : '');
}

function renderAddons() {
  var list = document.getElementById('addonList');
  var empty = document.getElementById('emptyState');
  list.innerHTML = '';
  if (addons.length === 0) {
    empty.style.display = 'block';
    return;
  }
  empty.style.display = 'none';
  addons.forEach(function(addon, i) {
    var li = document.createElement('li');
    li.className = 'addon-item';

    var isFirst = (i === 0);
    var isLast = (i === addons.length - 1);

    li.innerHTML =
      '<div class="addon-order">' +
        '<button class="btn-order" onclick="moveAddon(' + i + ',-1)"' + (isFirst ? ' disabled' : '') + '>' +
          '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 15l-6-6-6 6"/></svg>' +
        '</button>' +
        '<button class="btn-order" onclick="moveAddon(' + i + ',1)"' + (isLast ? ' disabled' : '') + '>' +
          '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>' +
        '</button>' +
      '</div>' +
      '<div class="addon-info">' +
        '<div class="addon-name">' + escapeHtml(addon.name || addon.url) +
          (addon.isNew ? '<span class="badge-new">New</span>' : '') +
        '</div>' +
        (addon.description ? '<div class="addon-desc">' + escapeHtml(addon.description) + '</div>' : '') +
        '<div class="addon-url">' + escapeHtml(addon.url) + '</div>' +
      '</div>' +
      '<div class="addon-actions">' +
        '<button class="btn btn-remove" onclick="removeAddon(' + i + ')">Remove</button>' +
      '</div>';

    list.appendChild(li);
  });
}

function renderCatalogs() {
  var list = document.getElementById('catalogList');
  var empty = document.getElementById('catalogEmptyState');
  list.innerHTML = '';

  if (catalogs.length === 0) {
    empty.style.display = 'block';
    return;
  }

  empty.style.display = 'none';
  catalogs.forEach(function(catalog, i) {
    var li = document.createElement('li');
    li.className = 'addon-item';

    var isFirst = (i === 0);
    var isLast = (i === catalogs.length - 1);
    var toggleClass = catalog.isDisabled ? 'btn btn-toggle disabled' : 'btn btn-toggle';

    li.innerHTML =
      '<div class="addon-order">' +
        '<button class="btn-order" onclick="moveCatalog(' + i + ',-1)"' + (isFirst ? ' disabled' : '') + '>' +
          '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 15l-6-6-6 6"/></svg>' +
        '</button>' +
        '<button class="btn-order" onclick="moveCatalog(' + i + ',1)"' + (isLast ? ' disabled' : '') + '>' +
          '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>' +
        '</button>' +
      '</div>' +
      '<div class="catalog-info">' +
        '<div class="catalog-name">' + escapeHtml(formatCatalogTitle(catalog.catalogName, catalog.type)) +
          (catalog.isDisabled ? '<span class="badge-disabled">Disabled</span>' : '') +
        '</div>' +
        '<div class="catalog-meta">' + escapeHtml(catalog.addonName) + '</div>' +
      '</div>' +
      '<div class="addon-actions">' +
        '<button class="' + toggleClass + '" onclick="toggleCatalog(' + i + ')">' +
          (catalog.isDisabled ? 'Enable' : 'Disable') +
        '</button>' +
      '</div>';

    list.appendChild(li);
  });
}

function moveAddon(index, direction) {
  var newIndex = index + direction;
  if (newIndex < 0 || newIndex >= addons.length) return;
  var item = addons.splice(index, 1)[0];
  addons.splice(newIndex, 0, item);
  renderAddons();
}

function moveCatalog(index, direction) {
  var newIndex = index + direction;
  if (newIndex < 0 || newIndex >= catalogs.length) return;
  var item = catalogs.splice(index, 1)[0];
  catalogs.splice(newIndex, 0, item);
  renderCatalogs();
}

function toggleCatalog(index) {
  var item = catalogs[index];
  if (!item) return;
  item.isDisabled = !item.isDisabled;
  renderCatalogs();
}

async function addAddon() {
  const input = document.getElementById('addonUrl');
  const errorEl = document.getElementById('addError');
  let url = input.value.trim();
  if (!url) return;

  if (url.startsWith('stremio://')) {
    url = url.replace(/^stremio:\/\//, 'https://');
  }
  if (!url.startsWith('http://') && !url.startsWith('https://')) {
    url = 'https://' + url;
  }
  if (url.endsWith('/manifest.json')) {
    url = url.replace(/\/manifest\.json$/, '');
  }
  url = url.replace(/\/+$/, '');

  if (addons.some(function(a) { return a.url === url; })) {
    errorEl.textContent = 'This addon is already in the list';
    errorEl.style.display = 'block';
    setTimeout(function() { errorEl.style.display = 'none'; }, 3000);
    return;
  }

  errorEl.style.display = 'none';
  addons.push({ url: url, name: url, description: null, isNew: true });
  input.value = '';
  renderAddons();
}

function removeAddon(index) {
  addons.splice(index, 1);
  renderAddons();
}

async function saveChanges() {
  var saveBtn = document.getElementById('saveBtn');
  saveBtn.disabled = true;

  var urls = addons.map(function(a) { return a.url; });
  var catalogOrderKeys = catalogs.map(function(c) { return c.key; });
  var disabledCatalogKeys = catalogs
    .filter(function(c) { return c.isDisabled; })
    .map(function(c) { return c.disableKey; });
  try {
    var res = await fetchWithTimeout('/api/addons', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        urls: urls,
        catalogOrderKeys: catalogOrderKeys,
        disabledCatalogKeys: disabledCatalogKeys
      })
    }, 8000);
    var data = await res.json();

    if (data.status === 'pending_confirmation') {
      showPendingStatus();
      pollStatus(data.id);
    } else if (data.error) {
      showErrorStatus(data.error);
      saveBtn.disabled = false;
    }
  } catch (e) {
    showErrorStatus('Failed to save. Check your connection to the TV.');
    saveBtn.disabled = false;
  }
}

function showPendingStatus() {
  var overlay = document.getElementById('statusOverlay');
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="spinner"></div></div>' +
    '<div class="status-title">Waiting for TV</div>' +
    '<div class="status-message">Please confirm the changes on your TV to apply them.</div>';
  content.className = 'status-content';
  overlay.classList.add('visible');
}

function showSuccessStatus() {
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="status-svg"><svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg></div></div>' +
    '<div class="status-title">Changes Applied</div>' +
    '<div class="status-message">Your addon configuration has been updated on the TV.</div>';
  content.className = 'status-content status-success';
  setTimeout(dismissStatus, 2500);
}

function showRejectedStatus() {
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="status-svg"><svg viewBox="0 0 24 24" fill="none" stroke="rgba(207,102,121,0.9)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6L6 18M6 6l12 12"/></svg></div></div>' +
    '<div class="status-title">Changes Rejected</div>' +
    '<div class="status-message">The changes were declined on the TV. Your list has been reverted.</div>';
  content.className = 'status-content status-rejected';
  setTimeout(function() {
    addons = JSON.parse(JSON.stringify(originalAddons));
    catalogs = JSON.parse(JSON.stringify(originalCatalogs));
    renderAddons();
    renderCatalogs();
    dismissStatus();
  }, 2500);
}

function showErrorStatus(msg) {
  var overlay = document.getElementById('statusOverlay');
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="status-svg"><svg viewBox="0 0 24 24" fill="none" stroke="rgba(207,102,121,0.9)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 8v4M12 16h.01"/></svg></div></div>' +
    '<div class="status-title">Something Went Wrong</div>' +
    '<div class="status-message">' + escapeHtml(msg) + '</div>' +
    '<div class="status-dismiss"><button class="btn" onclick="dismissStatus()">Dismiss</button></div>';
  content.className = 'status-content status-error';
  overlay.classList.add('visible');
}

function showTimeoutStatus() {
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="status-svg"><svg viewBox="0 0 24 24" fill="none" stroke="rgba(207,102,121,0.9)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg></div></div>' +
    '<div class="status-title">Timed Out</div>' +
    '<div class="status-message">No response from the TV. Please try again.</div>' +
    '<div class="status-dismiss"><button class="btn" onclick="dismissStatus()">Dismiss</button></div>';
  content.className = 'status-content status-error';
}

function showDisconnectedStatus() {
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="status-svg"><svg viewBox="0 0 24 24" fill="none" stroke="rgba(207,102,121,0.9)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 1l22 22M16.72 11.06A10.94 10.94 0 0 1 19 12.55M5 12.55a10.94 10.94 0 0 1 5.17-2.39M10.71 5.05A16 16 0 0 1 22.56 9M1.42 9a15.91 15.91 0 0 1 4.7-2.88M8.53 16.11a6 6 0 0 1 6.95 0M12 20h.01"/></svg></div></div>' +
    '<div class="status-title">Connection Lost</div>' +
    '<div class="status-message">The TV server is no longer reachable. The changes may have been applied.</div>' +
    '<div class="status-dismiss"><button class="btn" onclick="dismissStatus()">Dismiss</button></div>';
  content.className = 'status-content status-error';
}

function dismissStatus() {
  var overlay = document.getElementById('statusOverlay');
  overlay.classList.remove('visible');
  document.getElementById('saveBtn').disabled = false;
  if (pollTimer) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

async function pollStatus(changeId) {
  pollStartTime = Date.now();
  consecutiveErrors = 0;

  var poll = async function() {
    if (Date.now() - pollStartTime > POLL_TIMEOUT) {
      showTimeoutStatus();
      document.getElementById('saveBtn').disabled = false;
      return;
    }

    try {
      var res = await fetchWithTimeout('/api/status/' + changeId, {}, 4000);
      var data = await res.json();
      consecutiveErrors = 0;

      if (data.status === 'confirmed') {
        showSuccessStatus();
        setTimeout(function() {
          loadState();
          document.getElementById('saveBtn').disabled = false;
        }, 2000);
      } else if (data.status === 'rejected') {
        showRejectedStatus();
      } else if (data.status === 'not_found') {
        showDisconnectedStatus();
        document.getElementById('saveBtn').disabled = false;
      } else {
        pollTimer = setTimeout(poll, POLL_INTERVAL);
      }
    } catch (e) {
      consecutiveErrors++;
      if (consecutiveErrors >= 3) {
        showDisconnectedStatus();
        document.getElementById('saveBtn').disabled = false;
      } else {
        pollTimer = setTimeout(poll, 2000);
      }
    }
  };
  poll();
}

function escapeHtml(str) {
  var div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

function formatCatalogTitle(name, type) {
  var safeName = name || '';
  var safeType = type || '';
  if (!safeType) return safeName;
  return safeName + ' - ' + toTitleCase(safeType);
}

function toTitleCase(value) {
  if (!value) return '';
  return value.charAt(0).toUpperCase() + value.slice(1);
}

document.getElementById('addonUrl').addEventListener('keydown', function(e) {
  if (e.key === 'Enter') addAddon();
});

loadState();
</script>
</body>
</html>
""".trimIndent()
}
