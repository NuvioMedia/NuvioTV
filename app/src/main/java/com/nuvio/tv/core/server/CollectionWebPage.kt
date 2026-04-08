package com.nuvio.tv.core.server

import android.content.Context
import android.content.res.Configuration
import com.nuvio.tv.R
import java.util.Locale

object CollectionWebPage {

    fun getHtml(baseContext: Context): String {
        val tag = baseContext.getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        val context = if (!tag.isNullOrEmpty()) {
            val config = Configuration(baseContext.resources.configuration)
            config.setLocale(Locale.forLanguageTag(tag))
            baseContext.createConfigurationContext(config)
        } else baseContext
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>${context.getString(R.string.app_name)} - ${context.getString(R.string.web_manage_collections_title)}</title>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800;900&display=swap" rel="stylesheet">
<style>
  * { margin:0; padding:0; box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
  *:focus, *:active { outline:none !important; }
  body {
    font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
    background:#000; color:#fff; min-height:100vh; line-height:1.5;
  }
  .page { max-width:600px; margin:0 auto; padding:0 1.5rem 6rem; }
  .header {
    text-align:center; padding:3rem 0 2.5rem;
    border-bottom:1px solid rgba(255,255,255,0.05); margin-bottom:2.5rem;
  }
  .header-logo { height:40px; width:auto; margin-bottom:0.5rem; filter:brightness(0) invert(1); opacity:0.9; }
  .header p { font-size:0.875rem; font-weight:300; color:rgba(255,255,255,0.4); letter-spacing:0.02em; }
  .btn {
    display:inline-flex; align-items:center; justify-content:center; gap:0.5rem;
    background:transparent; border:1px solid rgba(255,255,255,0.2); border-radius:100px;
    padding:0.875rem 1.5rem; color:#fff; font-family:inherit; font-size:0.875rem; font-weight:500;
    cursor:pointer; transition:all 0.3s ease; white-space:nowrap; -webkit-tap-highlight-color:transparent;
  }
  .btn:hover { background:#fff; color:#000; border-color:#fff; }
  .btn:active { transform:scale(0.97); }
  .btn-save {
    width:100%; padding:1rem; font-size:0.95rem; font-weight:600; margin-top:2rem;
  }
  .btn-save:disabled { opacity:0.2; cursor:not-allowed; pointer-events:none; }
  .empty-state {
    text-align:center; color:rgba(255,255,255,0.2); padding:3rem 0;
    font-size:0.875rem; font-weight:300; display:none;
  }
  .action-bar { display:flex; gap:0.5rem; margin-bottom:1.5rem; }
  .action-bar .btn { flex:1; padding:0.75rem 1rem; font-size:0.85rem; }
  .status-overlay {
    position:fixed; top:0; left:0; width:100%; height:100%;
    background:rgba(0,0,0,0.92); backdrop-filter:blur(20px); -webkit-backdrop-filter:blur(20px);
    z-index:500; display:flex; align-items:center; justify-content:center;
    opacity:0; visibility:hidden; transition:all 0.3s ease;
  }
  .status-overlay.visible { opacity:1; visibility:visible; }
  .status-content { text-align:center; max-width:340px; padding:2rem; }
  .status-icon { margin-bottom:1.5rem; }
  .spinner {
    width:40px; height:40px; border:2px solid rgba(255,255,255,0.1);
    border-top-color:#fff; border-radius:50%; animation:spin 0.8s linear infinite; margin:0 auto;
  }
  @keyframes spin { to { transform:rotate(360deg); } }
  .status-title { font-size:1.25rem; font-weight:700; letter-spacing:-0.02em; margin-bottom:0.5rem; }
  .status-message { font-size:0.875rem; font-weight:300; color:rgba(255,255,255,0.4); line-height:1.6; }
  .status-success .status-title { color:#fff; }
  .status-rejected .status-title { color:rgba(207,102,121,0.9); }
  .status-error .status-title { color:rgba(207,102,121,0.9); }
  .status-dismiss { margin-top:1.5rem; }
  .status-svg { width:40px; height:40px; margin:0 auto; }
  .status-svg svg { width:40px; height:40px; }
  .connection-bar {
    position:fixed; top:0; left:0; right:0; background:rgba(207,102,121,0.15);
    border-bottom:1px solid rgba(207,102,121,0.3); padding:0.75rem 1.5rem;
    text-align:center; font-size:0.8rem; font-weight:500; color:rgba(207,102,121,0.9);
    z-index:600; display:none;
  }
  .connection-bar.visible { display:block; }
  .collection-card {
    background:rgba(255,255,255,0.03); border:1px solid rgba(255,255,255,0.07);
    border-radius:16px; margin-bottom:1rem; overflow:hidden; transition:opacity 0.2s;
  }
  .collection-header {
    display:flex; align-items:center; gap:0.5rem; padding:0.875rem 1rem; cursor:pointer;
    -webkit-tap-highlight-color:transparent; user-select:none;
  }
  .collection-title-input {
    flex:1; background:transparent; border:none; border-bottom:1px solid rgba(255,255,255,0.1);
    border-radius:0; padding:0.25rem 0; color:#fff; font-family:inherit;
    font-size:1rem; font-weight:600; letter-spacing:-0.01em;
  }
  .collection-title-input:focus { border-bottom-color:rgba(255,255,255,0.5); outline:none; }
  .collection-title-input::placeholder { color:rgba(255,255,255,0.2); }
  .col-actions { display:flex; align-items:center; gap:0.35rem; flex-shrink:0; }
  .col-meta-label {
    font-size:0.7rem; font-weight:500; color:rgba(255,255,255,0.3); text-transform:uppercase;
    letter-spacing:0.06em; min-width:60px; flex-shrink:0;
  }
  .folder-summary { font-size:0.75rem; color:rgba(255,255,255,0.25); padding:0 1rem 0.75rem; }
  .collapse-arrow {
    display:inline-flex; align-items:center; justify-content:center; width:24px; height:24px;
    transition:transform 0.25s ease; font-size:0.8rem; color:rgba(255,255,255,0.25); flex-shrink:0;
  }
  .collapse-arrow.open { transform:rotate(90deg); }
  .col-settings {
    padding:0.5rem 1rem 0.75rem; display:flex; flex-direction:column; gap:0.6rem;
    border-top:1px solid rgba(255,255,255,0.05);
  }
  .col-setting-row { display:flex; align-items:center; gap:0.6rem; min-height:36px; }
  .col-setting-row select, .col-setting-row input[type="url"] {
    flex:1; background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.08);
    border-radius:8px; padding:0.45rem 0.6rem; color:#fff; font-family:inherit; font-size:0.8rem; min-width:0;
  }
  .col-setting-row select:focus, .col-setting-row input:focus { border-color:rgba(255,255,255,0.25); outline:none; }
  .col-setting-row select option { background:#111; color:#fff; }
  .col-setting-row img {
    width:44px; height:25px; object-fit:cover; border-radius:4px;
    border:1px solid rgba(255,255,255,0.08); flex-shrink:0;
  }
  .toggle-switch {
    position:relative; width:40px; height:22px; flex-shrink:0; cursor:pointer;
    -webkit-tap-highlight-color:transparent;
  }
  .toggle-switch input { opacity:0; width:0; height:0; position:absolute; }
  .toggle-track { position:absolute; inset:0; background:rgba(255,255,255,0.12); border-radius:11px; transition:background 0.2s; }
  .toggle-switch input:checked + .toggle-track { background:rgba(255,255,255,0.85); }
  .toggle-thumb {
    position:absolute; top:2px; left:2px; width:18px; height:18px; background:#000;
    border-radius:50%; transition:transform 0.2s; box-shadow:0 1px 3px rgba(0,0,0,0.4);
  }
  .toggle-switch input:checked ~ .toggle-thumb { transform:translateX(18px); background:#000; }
  .toggle-switch:not(:has(input:checked)) .toggle-thumb { background:rgba(255,255,255,0.6); }
  .toggle-label { font-size:0.8rem; color:rgba(255,255,255,0.5); flex:1; }
  .folder-card {
    background:rgba(255,255,255,0.025); border-top:1px solid rgba(255,255,255,0.05); padding:0; margin:0;
  }
  .folder-card:last-of-type { border-bottom:1px solid rgba(255,255,255,0.05); }
  .folder-header {
    display:flex; align-items:center; gap:0.5rem; padding:0.65rem 1rem; cursor:pointer;
    -webkit-tap-highlight-color:transparent; user-select:none;
  }
  .folder-title-input {
    flex:1; background:transparent; border:none; border-bottom:1px solid rgba(255,255,255,0.08);
    border-radius:0; padding:0.2rem 0; color:#fff; font-family:inherit; font-size:0.875rem; font-weight:500;
  }
  .folder-title-input:focus { border-bottom-color:rgba(255,255,255,0.4); outline:none; }
  .folder-title-input::placeholder { color:rgba(255,255,255,0.2); }
  .folder-settings {
    padding:0.75rem 1rem 1rem; display:flex; flex-direction:column; gap:0.75rem;
  }
  .folder-settings-group { background:rgba(255,255,255,0.03); border-radius:10px; overflow:hidden; }
  .folder-settings-group-label {
    font-size:0.65rem; font-weight:600; color:rgba(255,255,255,0.25); text-transform:uppercase;
    letter-spacing:0.08em; padding:0.6rem 0.75rem 0.35rem;
  }
  .folder-setting-item {
    display:flex; align-items:center; gap:0.5rem; padding:0.5rem 0.75rem; min-height:40px;
  }
  .folder-setting-item + .folder-setting-item { border-top:1px solid rgba(255,255,255,0.04); }
  .folder-setting-item select, .folder-setting-item input[type="url"] {
    flex:1; background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.08);
    border-radius:8px; padding:0.4rem 0.55rem; color:#fff; font-family:inherit; font-size:0.8rem; min-width:0;
  }
  .folder-setting-item select:focus, .folder-setting-item input:focus { border-color:rgba(255,255,255,0.25); outline:none; }
  .folder-setting-item select option { background:#111; color:#fff; }
  .folder-setting-item img {
    width:32px; height:32px; object-fit:cover; border-radius:6px;
    border:1px solid rgba(255,255,255,0.08); flex-shrink:0;
  }
  .folder-setting-label { font-size:0.8rem; color:rgba(255,255,255,0.45); min-width:55px; flex-shrink:0; }
  .cover-mode-picker {
    display:flex; gap:0; border-radius:8px; overflow:hidden;
    border:1px solid rgba(255,255,255,0.08); flex:1;
  }
  .cover-mode-btn {
    flex:1; background:transparent; border:none; color:rgba(255,255,255,0.4); font-family:inherit;
    font-size:0.75rem; font-weight:500; padding:0.45rem 0.5rem; cursor:pointer; transition:all 0.2s;
    -webkit-tap-highlight-color:transparent;
  }
  .cover-mode-btn + .cover-mode-btn { border-left:1px solid rgba(255,255,255,0.06); }
  .cover-mode-btn.active { background:rgba(255,255,255,0.1); color:#fff; }
  .cover-mode-btn:hover:not(.active) { background:rgba(255,255,255,0.05); }
  .emoji-picker-btn {
    display:inline-flex; align-items:center; justify-content:center; min-width:36px; height:36px;
    background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.1); border-radius:8px;
    font-size:1.2rem; cursor:pointer; transition:all 0.2s; padding:0 0.4rem;
    -webkit-tap-highlight-color:transparent;
  }
  .emoji-picker-btn:hover { border-color:rgba(255,255,255,0.25); background:rgba(255,255,255,0.08); }
  .emoji-grid-wrap {
    display:none; margin-top:0.5rem; background:rgba(255,255,255,0.04);
    border:1px solid rgba(255,255,255,0.08); border-radius:10px; padding:0.6rem;
  }
  .emoji-grid-wrap.open { display:block; }
  .emoji-grid-search {
    width:100%; background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.08);
    border-radius:8px; padding:0.5rem 0.65rem; color:#fff; font-family:inherit; font-size:0.8rem;
    margin-bottom:0.5rem;
  }
  .emoji-grid-search:focus { border-color:rgba(255,255,255,0.25); outline:none; }
  .emoji-grid { max-height:220px; overflow-y:auto; }
  .emoji-grid [data-cat] { display:grid; grid-template-columns:repeat(8,1fr); gap:2px; }
  .emoji-cat-label { grid-column:1/-1; }
  .emoji-cell {
    display:flex; align-items:center; justify-content:center; width:100%; aspect-ratio:1;
    font-size:1.25rem; cursor:pointer; border-radius:8px; transition:background 0.15s;
    border:none; background:transparent; -webkit-tap-highlight-color:transparent;
  }
  .emoji-cell:hover { background:rgba(255,255,255,0.1); }
  .source-item {
    display:flex; align-items:center; gap:0.4rem; padding:0.4rem 0.75rem;
    font-size:0.78rem; color:rgba(255,255,255,0.55);
  }
  .source-item + .source-item { border-top:1px solid rgba(255,255,255,0.04); }
  .source-label { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .source-search-input {
    width:100%; background:rgba(255,255,255,0.05); border:1px solid rgba(255,255,255,0.08);
    border-radius:8px; padding:0.5rem 0.65rem; color:#fff; font-family:inherit; font-size:0.8rem;
    margin-bottom:0.25rem;
  }
  .source-search-input:focus { border-color:rgba(255,255,255,0.25); outline:none; }
  .btn-order {
    display:flex; align-items:center; justify-content:center; width:28px; height:28px;
    background:transparent; border:1px solid rgba(255,255,255,0.1); border-radius:6px;
    color:rgba(255,255,255,0.4); font-size:0.7rem; cursor:pointer; transition:all 0.2s ease;
    padding:0; -webkit-tap-highlight-color:transparent;
  }
  .btn-order:hover { background:rgba(255,255,255,0.08); border-color:rgba(255,255,255,0.25); color:#fff; }
  .btn-order:active { transform:scale(0.9); }
  .btn-order:disabled { opacity:0.15; cursor:not-allowed; pointer-events:none; }
  .btn-icon {
    display:inline-flex; align-items:center; justify-content:center; width:26px; height:26px;
    background:transparent; border:1px solid rgba(255,255,255,0.08); border-radius:6px;
    color:rgba(255,255,255,0.35); cursor:pointer; transition:all 0.2s; padding:0; flex-shrink:0;
    -webkit-tap-highlight-color:transparent;
  }
  .btn-icon:hover { background:rgba(255,255,255,0.08); border-color:rgba(255,255,255,0.2); color:#fff; }
  .btn-icon:disabled { opacity:0.15; cursor:not-allowed; pointer-events:none; }
  .btn-icon.danger { border-color:rgba(207,102,121,0.25); color:rgba(207,102,121,0.6); }
  .btn-icon.danger:hover { background:rgba(207,102,121,0.12); color:#CF6679; }
  .sources-filtering .btn-icon:not(.danger) { opacity:0.15; pointer-events:none; }
  .import-overlay {
    position:fixed; top:0; left:0; width:100%; height:100%;
    background:rgba(0,0,0,0.92); backdrop-filter:blur(20px); -webkit-backdrop-filter:blur(20px);
    z-index:500; display:none; align-items:center; justify-content:center; padding:1.5rem;
  }
  .import-overlay.visible { display:flex; }
  .import-modal {
    background:#111; border:1px solid rgba(255,255,255,0.1); border-radius:16px;
    padding:1.5rem; width:100%; max-width:500px;
  }
  .import-tab { display:none; }
  .import-tab.active { display:block; }
  .import-tab-btn { font-size:0.8rem !important; padding:0.5rem 1rem !important; flex:1; }
  .import-tab-btn.active { background:rgba(255,255,255,0.1); border-color:rgba(255,255,255,0.3); }
  .confirm-actions { display:flex; gap:0.75rem; margin-top:1.25rem; }
  .confirm-copy { font-size:0.9rem; color:rgba(255,255,255,0.55); line-height:1.6; }
  .btn-danger {
    background:rgba(207,102,121,0.12); border-color:rgba(207,102,121,0.35); color:#fff;
  }
  .btn-danger:hover { background:#CF6679; border-color:#CF6679; color:#fff; }
  @media (max-width:480px) {
    .page { padding:0 1rem 5rem; }
    .header { padding:2rem 0 2rem; }
    .header-logo { height:32px; }
  }
</style>
</head>
<body>
<div class="page">
  <div class="header">
    <img src="/logo.png" alt="Nuvio" class="header-logo">
    <p>${context.getString(R.string.web_manage_collections_subtitle)}</p>
  </div>

  <div class="action-bar">
    <button class="btn" onclick="addCollection()">+ New</button>
    <button class="btn" onclick="exportCollections()">Export</button>
    <button class="btn" onclick="showImportModal()">Import</button>
  </div>

  <div id="collectionsList"></div>
  <div class="empty-state" id="emptyState">No collections yet. Tap "+ New" to create one.</div>

  <div class="import-overlay" id="importOverlay">
    <div class="import-modal">
      <div style="font-size:1.1rem;font-weight:700;margin-bottom:1rem">Import Collections</div>
      <div style="display:flex;gap:0.5rem;margin-bottom:1rem">
        <button class="btn import-tab-btn active" onclick="switchImportTab('paste')">Paste</button>
        <button class="btn import-tab-btn" onclick="switchImportTab('file')">File</button>
        <button class="btn import-tab-btn" onclick="switchImportTab('url')">URL</button>
      </div>
      <div id="import-tab-paste" class="import-tab active">
        <textarea id="importJsonInput" placeholder="Paste collections JSON here..." style="width:100%;min-height:120px;background:rgba(255,255,255,0.05);border:1px solid rgba(255,255,255,0.12);border-radius:8px;padding:0.75rem;color:#fff;font-family:monospace;font-size:0.8rem;resize:vertical"></textarea>
      </div>
      <div id="import-tab-file" class="import-tab">
        <label style="display:block;text-align:center;padding:2rem;border:2px dashed rgba(255,255,255,0.15);border-radius:12px;cursor:pointer;color:rgba(255,255,255,0.4);font-size:0.85rem;transition:border-color 0.2s" id="fileDropLabel">
          <input type="file" id="importFileInput" accept=".json,application/json" style="display:none" onchange="onFileSelected(this)">
          Tap to select a .json file
          <div id="fileSelectedName" style="color:#fff;font-weight:600;margin-top:0.5rem;display:none"></div>
        </label>
      </div>
      <div id="import-tab-url" class="import-tab">
        <input type="url" id="importUrlInput" placeholder="https://example.com/collections.json" style="width:100%;background:transparent;border:1px solid rgba(255,255,255,0.12);border-radius:100px;padding:0.875rem 1.25rem;color:#fff;font-family:inherit;font-size:0.9rem">
      </div>
      <div id="importError" style="color:rgba(207,102,121,0.9);font-size:0.8rem;margin-top:0.5rem;display:none"></div>
      <div id="importSuccess" style="color:rgba(130,200,130,0.9);font-size:0.8rem;margin-top:0.5rem;display:none"></div>
      <div style="display:flex;gap:0.75rem;margin-top:1rem">
        <button class="btn" onclick="dismissImportModal()" style="flex:1">Cancel</button>
        <button class="btn" onclick="doImport()" style="flex:1" id="importBtn">Import</button>
      </div>
    </div>
  </div>

  <div class="import-overlay" id="deleteConfirmOverlay">
    <div class="import-modal">
      <div id="deleteConfirmTitle" style="font-size:1.1rem;font-weight:700;margin-bottom:0.75rem">Delete Item</div>
      <div id="deleteConfirmMessage" class="confirm-copy"></div>
      <div class="confirm-actions">
        <button class="btn" onclick="dismissDeleteConfirm()" style="flex:1">Cancel</button>
        <button class="btn btn-danger" id="deleteConfirmAction" onclick="confirmDeleteAction()" style="flex:1">Delete</button>
      </div>
    </div>
  </div>

  <button class="btn btn-save" id="saveBtn" onclick="saveChanges()">${context.getString(R.string.web_btn_save)}</button>
</div>

<div class="status-overlay" id="statusOverlay">
  <div class="status-content" id="statusContent"></div>
</div>

<div class="connection-bar" id="connectionBar">${context.getString(R.string.web_connection_lost)}</div>

<script>
var collections = [];
var originalCollections = [];
var availableCatalogs = [];
var pollTimer = null;
var pollStartTime = 0;
var POLL_TIMEOUT = 120000;
var POLL_INTERVAL = 1500;
var connectionLost = false;
var consecutiveErrors = 0;
var expandedCollection = null;
var expandedFolder = null;
var openEmojiPicker = null;
var pendingDeleteAction = null;

var EMOJI_CATEGORIES = [
  {name:'Streaming', emojis:['🎬','🎭','🎥','📺','🍿','🎞️','📽️','🎦','📡','📻']},
  {name:'Genres', emojis:['💀','👻','🔪','💣','🚀','🛸','🧙','🦸','🧟','🤖','💘','😂','😱','🤯','🥺','😈']},
  {name:'Sports', emojis:['⚽','🏀','🏈','⚾','🎾','🏐','🏒','🥊','🏎️','🏆','🎯','🏋️']},
  {name:'Music', emojis:['🎵','🎶','🎤','🎸','🥁','🎹','🎷','🎺','🎻','🪗']},
  {name:'Nature', emojis:['🌍','🌊','🏔️','🌋','🌅','🌙','⭐','🔥','❄️','🌈','🌸','🍀']},
  {name:'Animals', emojis:['🐕','🐈','🦁','🐻','🦊','🐺','🦅','🐉','🦋','🐬','🦈','🐙']},
  {name:'Food', emojis:['🍕','🍔','🍣','🍜','🍩','🍰','🍷','🍺','☕','🧁','🌮','🥗']},
  {name:'Travel', emojis:['✈️','🚂','🚗','⛵','🏖️','🗼','🏰','🗽','🎡','🏕️','🌆','🛣️']},
  {name:'People', emojis:['👨‍👩‍👧‍👦','👫','👶','🧒','👩','👨','🧓','💃','🕺','🥷','🧑‍🚀','🧑‍🎨']},
  {name:'Objects', emojis:['📱','💻','🎮','🕹️','📷','🔮','💡','🔑','💎','🎁','📚','✏️']},
  {name:'Flags', emojis:[
    '🏳️‍🌈','🏴‍☠️',
    '🇦🇫','🇦🇱','🇩🇿','🇦🇸','🇦🇩','🇦🇴','🇦🇮','🇦🇬','🇦🇷','🇦🇲','🇦🇼','🇦🇺',
    '🇦🇹','🇦🇿','🇧🇸','🇧🇭','🇧🇩','🇧🇧','🇧🇾','🇧🇪','🇧🇿','🇧🇯','🇧🇲','🇧🇹',
    '🇧🇴','🇧🇦','🇧🇼','🇧🇷','🇧🇳','🇧🇬','🇧🇫','🇧🇮','🇰🇭','🇨🇲','🇨🇦','🇨🇻',
    '🇨🇫','🇹🇩','🇨🇱','🇨🇳','🇨🇴','🇰🇲','🇨🇬','🇨🇩','🇨🇷','🇨🇮','🇭🇷','🇨🇺',
    '🇨🇼','🇨🇾','🇨🇿','🇩🇰','🇩🇯','🇩🇲','🇩🇴','🇪🇨','🇪🇬','🇸🇻','🇬🇶','🇪🇷',
    '🇪🇪','🇸🇿','🇪🇹','🇫🇯','🇫🇮','🇫🇷','🇬🇦','🇬🇲','🇬🇪','🇩🇪','🇬🇭','🇬🇷',
    '🇬🇩','🇬🇹','🇬🇳','🇬🇼','🇬🇾','🇭🇹','🇭🇳','🇭🇰','🇭🇺','🇮🇸','🇮🇳','🇮🇩',
    '🇮🇷','🇮🇶','🇮🇪','🇮🇱','🇮🇹','🇯🇲','🇯🇵','🇯🇴','🇰🇿','🇰🇪','🇰🇮','🇰🇼',
    '🇰🇬','🇱🇦','🇱🇻','🇱🇧','🇱🇸','🇱🇷','🇱🇾','🇱🇮','🇱🇹','🇱🇺','🇲🇴','🇲🇬',
    '🇲🇼','🇲🇾','🇲🇻','🇲🇱','🇲🇹','🇲🇷','🇲🇺','🇲🇽','🇫🇲','🇲🇩','🇲🇨','🇲🇳',
    '🇲🇪','🇲🇦','🇲🇿','🇲🇲','🇳🇦','🇳🇷','🇳🇵','🇳🇱','🇳🇿','🇳🇮','🇳🇪','🇳🇬',
    '🇰🇵','🇲🇰','🇳🇴','🇴🇲','🇵🇰','🇵🇼','🇵🇸','🇵🇦','🇵🇬','🇵🇾','🇵🇪','🇵🇭',
    '🇵🇱','🇵🇹','🇵🇷','🇶🇦','🇷🇴','🇷🇺','🇷🇼','🇰🇳','🇱🇨','🇻🇨','🇼🇸','🇸🇲',
    '🇸🇹','🇸🇦','🇸🇳','🇷🇸','🇸🇨','🇸🇱','🇸🇬','🇸🇰','🇸🇮','🇸🇧','🇸🇴','🇿🇦',
    '🇰🇷','🇸🇸','🇪🇸','🇱🇰','🇸🇩','🇸🇷','🇸🇪','🇨🇭','🇸🇾','🇹🇼','🇹🇯','🇹🇿',
    '🇹🇭','🇹🇱','🇹🇬','🇹🇴','🇹🇹','🇹🇳','🇹🇷','🇹🇲','🇹🇻','🇺🇬','🇺🇦','🇦🇪',
    '🇬🇧','🇺🇸','🇺🇾','🇺🇿','🇻🇺','🇻🇪','🇻🇳','🇾🇪','🇿🇲','🇿🇼'
  ]},
  {name:'Symbols', emojis:['❤️','💜','💙','💚','💛','🧡','🖤','🤍','✅','❌','⚡','💯']}
];

function generateId() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    var r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

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
    collections = state.collections || [];
    availableCatalogs = state.availableCatalogs || [];
    originalCollections = JSON.parse(JSON.stringify(collections));
    setConnectionLost(false);
    renderCollections();
  } catch (e) {
    setConnectionLost(true);
  }
}

function setConnectionLost(lost) {
  connectionLost = lost;
  document.getElementById('connectionBar').className = 'connection-bar' + (lost ? ' visible' : '');
}

function escapeHtml(str) {
  var div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

function escapeAttr(str) {
  if (!str) return '';
  return str.replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

function toTitleCase(value) {
  if (!value) return '';
  return value.charAt(0).toUpperCase() + value.slice(1);
}

function toggleCollectionExpand(ci) {
  expandedCollection = (expandedCollection === ci) ? null : ci;
  expandedFolder = null;
  renderCollections();
}

function toggleFolderExpand(ci, fi) {
  var key = ci + '-' + fi;
  expandedFolder = (expandedFolder === key) ? null : key;
  renderCollections();
}

function toggleEmojiPicker(ci, fi) {
  var id = 'emoji-grid-' + ci + '-' + fi;
  var el = document.getElementById(id);
  if (!el) return;
  if (openEmojiPicker && openEmojiPicker !== id) {
    var prev = document.getElementById(openEmojiPicker);
    if (prev) prev.classList.remove('open');
  }
  el.classList.toggle('open');
  openEmojiPicker = el.classList.contains('open') ? id : null;
}

function selectEmoji(ci, fi, catIdx, emojiIdx) {
  var emoji = EMOJI_CATEGORIES[catIdx].emojis[emojiIdx];
  collections[ci].folders[fi].coverEmoji = emoji;
  var el = document.getElementById('emoji-grid-' + ci + '-' + fi);
  if (el) el.classList.remove('open');
  openEmojiPicker = null;
  renderCollections();
}

function filterEmoji(ci, fi, query) {
  var container = document.getElementById('emoji-cells-' + ci + '-' + fi);
  if (!container) return;
  var q = query.toLowerCase();
  var sections = container.querySelectorAll('[data-cat]');
  sections.forEach(function(sec) {
    var catName = sec.getAttribute('data-cat').toLowerCase();
    var cells = sec.querySelectorAll('.emoji-cell');
    var anyVisible = false;
    cells.forEach(function(cell) {
      var show = !q || catName.indexOf(q) >= 0;
      cell.style.display = show ? '' : 'none';
      if (show) anyVisible = true;
    });
    var label = sec.querySelector('.emoji-cat-label');
    if (label) label.style.display = anyVisible ? '' : 'none';
  });
}

function filterCatalogSources(ci, fi, query) {
  var container = document.getElementById('src-list-' + ci + '-' + fi);
  if (!container) return;
  var q = query.toLowerCase();
  var items = container.children;
  for (var i = 0; i < items.length; i++) {
    var text = items[i].getAttribute('data-label') || '';
    items[i].style.display = (!q || text.toLowerCase().indexOf(q) >= 0) ? '' : 'none';
  }
}

function filterActiveSources(ci, fi, query) {
  var container = document.getElementById('active-src-list-' + ci + '-' + fi);
  if (!container) return;
  var q = query.toLowerCase();
  if (q) { container.classList.add('sources-filtering'); }
  else { container.classList.remove('sources-filtering'); }
  var items = container.querySelectorAll('.source-item');
  for (var i = 0; i < items.length; i++) {
    var text = items[i].textContent || '';
    items[i].style.display = (!q || text.toLowerCase().indexOf(q) >= 0) ? '' : 'none';
  }
}

function addCollection() {
  collections.push({ id: generateId(), title: 'New Collection', backdropImageUrl: null, pinToTop: false, focusGlowEnabled: true, viewMode: 'TABBED_GRID', showAllTab: true, folders: [] });
  expandedCollection = collections.length - 1;
  expandedFolder = null;
  renderCollections();
}

function removeCollection(ci) {
  var collection = collections[ci];
  if (!collection) return;
  showDeleteConfirm(
    'Delete Collection',
    'Delete "' + escapeHtml(collection.title || 'Untitled Collection') + '" and all of its folders?',
    function() {
  collections.splice(ci, 1);
  if (expandedCollection === ci) expandedCollection = null;
  else if (expandedCollection !== null && expandedCollection > ci) expandedCollection--;
  renderCollections();
    }
  );
}

function moveCollection(ci, dir) {
  var ni = ci + dir;
  if (ni < 0 || ni >= collections.length) return;
  var item = collections.splice(ci, 1)[0];
  collections.splice(ni, 0, item);
  if (expandedCollection === ci) expandedCollection = ni;
  else if (expandedCollection === ni) expandedCollection = ci;
  renderCollections();
}

function updateCollectionTitle(ci, val) {
  collections[ci].title = val;
  updateSaveButtonState();
}

function updateCollectionBackdrop(ci, val) {
  collections[ci].backdropImageUrl = val || null;
  var img = document.getElementById('col-backdrop-preview-' + ci);
  if (val) {
    if (img) { img.src = val; img.style.display = ''; }
    else { renderCollections(); }
  } else {
    if (img) img.style.display = 'none';
  }
}

function updateCollectionViewMode(ci, val) {
  collections[ci].viewMode = val;
  renderCollections();
}

function updateCollectionShowAllTab(ci, checked) {
  collections[ci].showAllTab = checked;
}

function updateCollectionPinToTop(ci, checked) {
  collections[ci].pinToTop = checked;
}

function updateCollectionFocusGlow(ci, checked) {
  collections[ci].focusGlowEnabled = checked;
}

function addFolder(ci) {
  collections[ci].folders.push({ id: generateId(), title: 'New Folder', coverImageUrl: null, focusGifUrl: null, coverEmoji: null, tileShape: 'SQUARE', hideTitle: false, catalogSources: [] });
  expandedFolder = ci + '-' + (collections[ci].folders.length - 1);
  renderCollections();
}

function removeFolder(ci, fi) {
  var collection = collections[ci];
  var folder = collection && collection.folders ? collection.folders[fi] : null;
  if (!folder) return;
  showDeleteConfirm(
    'Delete Folder',
    'Delete folder "' + escapeHtml(folder.title || 'Untitled Folder') + '" from "' + escapeHtml(collection.title || 'Untitled Collection') + '"?',
    function() {
  collections[ci].folders.splice(fi, 1);
  if (expandedFolder === ci + '-' + fi) expandedFolder = null;
  renderCollections();
    }
  );
}

function moveFolder(ci, fi, dir) {
  var folders = collections[ci].folders;
  var ni = fi + dir;
  if (ni < 0 || ni >= folders.length) return;
  var item = folders.splice(fi, 1)[0];
  folders.splice(ni, 0, item);
  renderCollections();
}

function updateFolderTitle(ci, fi, val) {
  collections[ci].folders[fi].title = val;
}

function updateFolderCoverImage(ci, fi, val) {
  collections[ci].folders[fi].coverImageUrl = val || null;
  var img = document.getElementById('cover-preview-' + ci + '-' + fi);
  if (val) {
    if (img) { img.src = val; img.style.display = ''; }
    else { renderCollections(); }
  } else {
    if (img) img.style.display = 'none';
  }
}

function updateFolderFocusGifUrl(ci, fi, val) {
  collections[ci].folders[fi].focusGifUrl = val || null;
}

function updateFolderFocusGifEnabled(ci, fi, checked) {
  collections[ci].folders[fi].focusGifEnabled = checked;
}
  collections[ci].folders.push({ id: generateId(), title: 'New Folder', coverImageUrl: null, focusGifUrl: null, focusGifEnabled: true, coverEmoji: null, tileShape: 'SQUARE', hideTitle: false, catalogSources: [] });

function updateFolderTileShape(ci, fi, val) {
  collections[ci].folders[fi].tileShape = val;
}

function updateFolderHideTitle(ci, fi, checked) {
  collections[ci].folders[fi].hideTitle = checked;
}

function setFolderCoverMode(ci, fi, mode) {
  var folder = collections[ci].folders[fi];
  if (!folder._coverMode) folder._coverMode = folder.coverEmoji ? 'emoji' : (folder.coverImageUrl ? 'image' : 'none');
  folder._coverMode = mode;
  if (mode === 'none') { folder.coverEmoji = null; folder.coverImageUrl = null; }
  else if (mode === 'emoji') { folder.coverImageUrl = null; }
  else if (mode === 'image') { folder.coverEmoji = null; }
  renderCollections();
}

function addCatalogSourceByVal(ci, fi, val) {
  var parts = val.split('::');
  if (parts.length < 3) return;
  var src = { addonId: parts[0], type: parts[1], catalogId: parts[2] };
  var existing = collections[ci].folders[fi].catalogSources;
  var dup = existing.some(function(s) { return s.addonId === src.addonId && s.type === src.type && s.catalogId === src.catalogId; });
  if (dup) return;
  existing.push(src);
  renderCollections();
}

function removeCatalogSource(ci, fi, si) {
  collections[ci].folders[fi].catalogSources.splice(si, 1);
  renderCollections();
}

function moveCatalogSource(ci, fi, si, dir) {
  var sources = collections[ci].folders[fi].catalogSources;
  var ni = si + dir;
  if (ni < 0 || ni >= sources.length) return;
  var item = sources.splice(si, 1)[0];
  sources.splice(ni, 0, item);
  renderCollections();
}

function catalogSourceLabel(src) {
  var match = availableCatalogs.find(function(c) {
    return c.addonId === src.addonId && c.type === src.type && c.catalogId === src.catalogId;
  });
  if (match) return match.catalogName + ' - ' + toTitleCase(match.type) + ' (' + match.addonName + ')';
  return src.catalogId + ' - ' + toTitleCase(src.type) + ' (' + src.addonId + ')';
}

function getCollectionErrors(col) {
  var errors = [];
  if (!col.title || !col.title.trim()) errors.push('Missing title');
  if (!col.folders || col.folders.length === 0) errors.push('No folders');
  (col.folders || []).forEach(function(f, fi) {
    if (!f.catalogSources || f.catalogSources.length === 0) {
      errors.push((f.title || 'Folder ' + (fi + 1)) + ': no sources');
    }
  });
  return errors;
}

function showDeleteConfirm(title, message, onConfirm) {
  pendingDeleteAction = onConfirm;
  document.getElementById('deleteConfirmTitle').textContent = title;
  document.getElementById('deleteConfirmMessage').innerHTML = message;
  document.getElementById('deleteConfirmOverlay').classList.add('visible');
}

function dismissDeleteConfirm() {
  pendingDeleteAction = null;
  document.getElementById('deleteConfirmOverlay').classList.remove('visible');
}

function confirmDeleteAction() {
  var action = pendingDeleteAction;
  dismissDeleteConfirm();
  if (action) action();
}

function updateSaveButtonState() {
  var hasIssues = collections.some(function(col) { return getCollectionErrors(col).length > 0; });
  var saveBtn = document.getElementById('saveBtn');
  if (saveBtn && !saveBtn._polling) {
    saveBtn.style.opacity = hasIssues ? '0.35' : '';
    saveBtn.style.pointerEvents = hasIssues ? 'none' : '';
  }
}

function renderCollections() {
  var container = document.getElementById('collectionsList');
  var empty = document.getElementById('emptyState');
  container.innerHTML = '';
  if (collections.length === 0) { empty.style.display = 'block'; updateSaveButtonState(); return; }
  empty.style.display = 'none';

  collections.forEach(function(col, ci) {
    var card = document.createElement('div');
    card.className = 'collection-card';

    var isExpanded = (expandedCollection === ci);
    var folderCount = (col.folders || []).length;
    var errors = getCollectionErrors(col);

    var headerHtml =
      '<div class="collection-header" onclick="toggleCollectionExpand(' + ci + ')">' +
        '<span class="collapse-arrow' + (isExpanded ? ' open' : '') + '">&#9654;</span>' +
        '<input class="collection-title-input" value="' + escapeAttr(col.title) + '" onchange="updateCollectionTitle(' + ci + ',this.value)" onclick="event.stopPropagation()" placeholder="Collection name">' +
        (errors.length > 0 ? '<span style="font-size:0.6rem;font-weight:700;color:rgba(255,180,60,0.9);background:rgba(255,180,60,0.12);padding:0.2rem 0.5rem;border-radius:100px;flex-shrink:0">' + errors.length + ' issue' + (errors.length > 1 ? 's' : '') + '</span>' : '') +
        '<div class="col-actions" onclick="event.stopPropagation()">' +
          '<button class="btn-order" onclick="moveCollection(' + ci + ',-1)"' + (ci === 0 ? ' disabled' : '') + '>' +
            '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M18 15l-6-6-6 6"/></svg>' +
          '</button>' +
          '<button class="btn-order" onclick="moveCollection(' + ci + ',1)"' + (ci === collections.length - 1 ? ' disabled' : '') + '>' +
            '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M6 9l6 6 6-6"/></svg>' +
          '</button>' +
          '<button class="btn-icon danger" onclick="removeCollection(' + ci + ')" title="Remove">' +
            '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M18 6L6 18M6 6l12 12"/></svg>' +
          '</button>' +
        '</div>' +
      '</div>';

    if (!isExpanded) {
      card.innerHTML = headerHtml +
        '<div class="folder-summary">' + folderCount + ' folder' + (folderCount !== 1 ? 's' : '') + '</div>';
      container.appendChild(card);
      return;
    }

    var settingsHtml =
      '<div class="col-settings">' +
        '<div class="col-setting-row">' +
          '<span class="col-meta-label">Backdrop</span>' +
          '<img id="col-backdrop-preview-' + ci + '" src="' + escapeAttr(col.backdropImageUrl || '') + '" style="' + (col.backdropImageUrl ? '' : 'display:none') + '" onerror="this.style.display=\'none\'">' +
          '<input type="url" placeholder="Image URL (optional)" value="' + escapeAttr(col.backdropImageUrl || '') + '" oninput="updateCollectionBackdrop(' + ci + ',this.value)">' +
        '</div>' +
        '<div class="col-setting-row">' +
          '<span class="toggle-label">Pin above catalogs</span>' +
          '<label class="toggle-switch" onclick="event.stopPropagation()">' +
            '<input type="checkbox"' + (col.pinToTop ? ' checked' : '') + ' onchange="updateCollectionPinToTop(' + ci + ',this.checked)">' +
            '<span class="toggle-track"></span>' +
            '<span class="toggle-thumb"></span>' +
          '</label>' +
        '</div>' +
        '<div class="col-setting-row">' +
          '<span class="toggle-label">Focus glow on cards</span>' +
          '<label class="toggle-switch" onclick="event.stopPropagation()">' +
            '<input type="checkbox"' + (col.focusGlowEnabled !== false ? ' checked' : '') + ' onchange="updateCollectionFocusGlow(' + ci + ',this.checked)">' +
            '<span class="toggle-track"></span>' +
            '<span class="toggle-thumb"></span>' +
          '</label>' +
        '</div>' +
        '<div class="col-setting-row">' +
          '<span class="col-meta-label">View Mode</span>' +
          '<div class="cover-mode-picker">' +
            '<button class="cover-mode-btn' + ((col.viewMode === 'TABBED_GRID' || !col.viewMode) ? ' active' : '') + '" onclick="updateCollectionViewMode(' + ci + ',\'TABBED_GRID\')">Tabs</button>' +
            '<button class="cover-mode-btn' + (col.viewMode === 'ROWS' ? ' active' : '') + '" onclick="updateCollectionViewMode(' + ci + ',\'ROWS\')">Rows</button>' +
            '<button class="cover-mode-btn' + (col.viewMode === 'FOLLOW_LAYOUT' ? ' active' : '') + '" onclick="updateCollectionViewMode(' + ci + ',\'FOLLOW_LAYOUT\')">Follow Home</button>' +
          '</div>' +
        '</div>' +
        ((col.viewMode === 'TABBED_GRID' || !col.viewMode) ?
        '<div class="col-setting-row">' +
          '<span class="toggle-label">Show "All" tab</span>' +
          '<label class="toggle-switch" onclick="event.stopPropagation()">' +
            '<input type="checkbox"' + (col.showAllTab !== false ? ' checked' : '') + ' onchange="updateCollectionShowAllTab(' + ci + ',this.checked)">' +
            '<span class="toggle-track"></span>' +
            '<span class="toggle-thumb"></span>' +
          '</label>' +
        '</div>' : '') +
      '</div>';

    var foldersHtml = '';
    (col.folders || []).forEach(function(folder, fi) {
      var sourcesHtml = '';
      (folder.catalogSources || []).forEach(function(src, si) {
        var isFirstSrc = (si === 0);
        var isLastSrc = (si === folder.catalogSources.length - 1);
        sourcesHtml +=
          '<div class="source-item">' +
            '<button class="btn-icon" onclick="moveCatalogSource(' + ci + ',' + fi + ',' + si + ',-1)"' + (isFirstSrc ? ' disabled' : '') + '>' +
              '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M18 15l-6-6-6 6"/></svg>' +
            '</button>' +
            '<button class="btn-icon" onclick="moveCatalogSource(' + ci + ',' + fi + ',' + si + ',1)"' + (isLastSrc ? ' disabled' : '') + '>' +
              '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M6 9l6 6 6-6"/></svg>' +
            '</button>' +
            '<span class="source-label">' + escapeHtml(catalogSourceLabel(src)) + '</span>' +
            '<button class="btn-icon danger" onclick="removeCatalogSource(' + ci + ',' + fi + ',' + si + ')">' +
              '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M18 6L6 18M6 6l12 12"/></svg>' +
            '</button>' +
          '</div>';
      });

      var emojiCellsHtml = '';
      EMOJI_CATEGORIES.forEach(function(cat, catIdx) {
        emojiCellsHtml += '<div data-cat="' + cat.name + '">';
        emojiCellsHtml += '<div class="emoji-cat-label" style="grid-column:1/-1;font-size:0.65rem;font-weight:600;color:rgba(255,255,255,0.3);text-transform:uppercase;letter-spacing:0.06em;padding:0.5rem 0 0.25rem">' + escapeHtml(cat.name) + '</div>';
        cat.emojis.forEach(function(em, emIdx) {
          emojiCellsHtml += '<button class="emoji-cell" onclick="selectEmoji(' + ci + ',' + fi + ',' + catIdx + ',' + emIdx + ')">' + em + '</button>';
        });
        emojiCellsHtml += '</div>';
      });

      var existingSources = (folder.catalogSources || []);
      var sourceListHtml = '';
      availableCatalogs.forEach(function(c) {
        var val = c.addonId + '::' + c.type + '::' + c.catalogId;
        var alreadyAdded = existingSources.some(function(s) { return s.addonId === c.addonId && s.type === c.type && s.catalogId === c.catalogId; });
        var label = c.catalogName + ' - ' + toTitleCase(c.type) + ' (' + c.addonName + ')';
        if (alreadyAdded) {
          sourceListHtml += '<div class="source-item" data-label="' + escapeAttr(label) + '" style="padding:0.4rem 0.75rem;opacity:0.4">' +
            '<span class="source-label">' + escapeHtml(label) + '</span>' +
            '<span style="font-size:0.7rem;color:rgba(130,200,130,0.85);flex-shrink:0">Added</span>' +
          '</div>';
        } else {
          sourceListHtml += '<div class="source-item" data-label="' + escapeAttr(label) + '" style="cursor:pointer;padding:0.4rem 0.75rem" onclick="addCatalogSourceByVal(' + ci + ',' + fi + ',\'' + escapeAttr(val) + '\')">' +
            '<span class="source-label" style="color:rgba(255,255,255,0.45)">' + escapeHtml(label) + '</span>' +
            '<span style="font-size:0.7rem;color:rgba(255,255,255,0.2);flex-shrink:0">+ Add</span>' +
          '</div>';
        }
      });

      var isFolderExpanded = (expandedFolder === ci + '-' + fi);
      var srcCount = (folder.catalogSources || []).length;
      var coverMode = folder._coverMode || (folder.coverEmoji ? 'emoji' : (folder.coverImageUrl ? 'image' : 'none'));

      foldersHtml +=
        '<div class="folder-card">' +
          '<div class="folder-header" onclick="toggleFolderExpand(' + ci + ',' + fi + ')">' +
            '<span class="collapse-arrow' + (isFolderExpanded ? ' open' : '') + '">&#9654;</span>' +
            '<input class="folder-title-input" value="' + escapeAttr(folder.title) + '" onchange="updateFolderTitle(' + ci + ',' + fi + ',this.value)" onclick="event.stopPropagation()" placeholder="Folder name">' +
            (!isFolderExpanded ? '<span style="font-size:0.7rem;color:rgba(255,255,255,0.3);background:rgba(255,255,255,0.06);padding:0.15rem 0.5rem;border-radius:100px;flex-shrink:0">' + srcCount + ' source' + (srcCount !== 1 ? 's' : '') + '</span>' : '') +
            '<div class="col-actions" onclick="event.stopPropagation()">' +
              '<button class="btn-order" onclick="moveFolder(' + ci + ',' + fi + ',-1)"' + (fi === 0 ? ' disabled' : '') + ' style="width:22px;height:22px">' +
                '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M18 15l-6-6-6 6"/></svg>' +
              '</button>' +
              '<button class="btn-order" onclick="moveFolder(' + ci + ',' + fi + ',1)"' + (fi === col.folders.length - 1 ? ' disabled' : '') + ' style="width:22px;height:22px">' +
                '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M6 9l6 6 6-6"/></svg>' +
              '</button>' +
              '<button class="btn-icon danger" onclick="removeFolder(' + ci + ',' + fi + ')" style="width:22px;height:22px">' +
                '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M18 6L6 18M6 6l12 12"/></svg>' +
              '</button>' +
            '</div>' +
          '</div>' +
          (isFolderExpanded ?
          '<div class="folder-settings">' +
            '<div class="folder-settings-group">' +
              '<div class="folder-settings-group-label">Cover</div>' +
              '<div class="folder-setting-item">' +
                '<div class="cover-mode-picker">' +
                  '<button class="cover-mode-btn' + (coverMode === 'none' ? ' active' : '') + '" onclick="setFolderCoverMode(' + ci + ',' + fi + ',\'none\')">None</button>' +
                  '<button class="cover-mode-btn' + (coverMode === 'emoji' ? ' active' : '') + '" onclick="setFolderCoverMode(' + ci + ',' + fi + ',\'emoji\')">Emoji</button>' +
                  '<button class="cover-mode-btn' + (coverMode === 'image' ? ' active' : '') + '" onclick="setFolderCoverMode(' + ci + ',' + fi + ',\'image\')">Image</button>' +
                '</div>' +
              '</div>' +
              (coverMode === 'emoji' ?
              '<div class="folder-setting-item">' +
                '<button class="emoji-picker-btn" onclick="toggleEmojiPicker(' + ci + ',' + fi + ')">' +
                  (folder.coverEmoji ? escapeHtml(folder.coverEmoji) : '😀') +
                '</button>' +
                '<span style="font-size:0.78rem;color:rgba(255,255,255,0.3);flex:1">Tap to pick emoji</span>' +
              '</div>' +
              '<div id="emoji-grid-' + ci + '-' + fi + '" class="emoji-grid-wrap" style="margin:0 0.75rem 0.5rem">' +
                '<input class="emoji-grid-search" placeholder="Search emoji..." oninput="filterEmoji(' + ci + ',' + fi + ',this.value)">' +
                '<div class="emoji-grid" id="emoji-cells-' + ci + '-' + fi + '">' + emojiCellsHtml + '</div>' +
              '</div>' : '') +
              (coverMode === 'image' ?
              '<div class="folder-setting-item">' +
                '<img id="cover-preview-' + ci + '-' + fi + '" src="' + escapeAttr(folder.coverImageUrl || '') + '" style="' + (folder.coverImageUrl ? '' : 'display:none') + '" onerror="this.style.display=\'none\'">' +
                '<input type="url" placeholder="Cover image URL" value="' + escapeAttr(folder.coverImageUrl || '') + '" oninput="updateFolderCoverImage(' + ci + ',' + fi + ',this.value)">' +
              '</div>' : '') +
              '<div class="folder-setting-item">' +
                '<input type="url" placeholder="Focused GIF URL (optional)" value="' + escapeAttr(folder.focusGifUrl || '') + '" oninput="updateFolderFocusGifUrl(' + ci + ',' + fi + ',this.value)">' +
              '</div>' +
              '<div class="folder-setting-item">' +
                '<span class="toggle-label">Play GIF on focus</span>' +
                '<label class="toggle-switch">' +
                  '<input type="checkbox"' + (folder.focusGifEnabled !== false ? ' checked' : '') + ' onchange="updateFolderFocusGifEnabled(' + ci + ',' + fi + ',this.checked)">' +
                  '<span class="toggle-track"></span>' +
                  '<span class="toggle-thumb"></span>' +
                '</label>' +
              '</div>' +
            '</div>' +
            '<div class="folder-settings-group">' +
              '<div class="folder-settings-group-label">Display</div>' +
              '<div class="folder-setting-item">' +
                '<span class="folder-setting-label">Shape</span>' +
                '<select onchange="updateFolderTileShape(' + ci + ',' + fi + ',this.value)">' +
                  '<option value="POSTER"' + (folder.tileShape === 'POSTER' ? ' selected' : '') + '>Poster</option>' +
                  '<option value="LANDSCAPE"' + (folder.tileShape === 'LANDSCAPE' ? ' selected' : '') + '>Landscape</option>' +
                  '<option value="SQUARE"' + ((folder.tileShape === 'SQUARE' || !folder.tileShape) ? ' selected' : '') + '>Square</option>' +
                '</select>' +
              '</div>' +
              '<div class="folder-setting-item">' +
                '<span class="toggle-label">Hide title</span>' +
                '<label class="toggle-switch">' +
                  '<input type="checkbox"' + (folder.hideTitle ? ' checked' : '') + ' onchange="updateFolderHideTitle(' + ci + ',' + fi + ',this.checked)">' +
                  '<span class="toggle-track"></span>' +
                  '<span class="toggle-thumb"></span>' +
                '</label>' +
              '</div>' +
            '</div>' +
            '<div class="folder-settings-group">' +
              '<div class="folder-settings-group-label">Active Sources</div>' +
              '<div style="padding:0.5rem 0.75rem">' +
                '<input class="source-search-input" placeholder="Filter active sources..." oninput="filterActiveSources(' + ci + ',' + fi + ',this.value)">' +
                '<div id="active-src-list-' + ci + '-' + fi + '" style="max-height:180px;overflow-y:auto;border:1px solid rgba(255,255,255,0.05);border-radius:8px;margin-top:0.25rem">' +
                sourcesHtml +
                (sourcesHtml ? '' : '<div style="padding:0.4rem 0.5rem;font-size:0.78rem;color:rgba(255,255,255,0.2)">No sources added yet</div>') +
                '</div>' +
              '</div>' +
            '</div>' +
            '<div class="folder-settings-group" style="margin-top:0.5rem">' +
              '<div class="folder-settings-group-label">Add Sources</div>' +
              '<div style="padding:0.5rem 0.75rem">' +
                '<input class="source-search-input" placeholder="Search catalogs..." oninput="filterCatalogSources(' + ci + ',' + fi + ',this.value)">' +
                '<div id="src-list-' + ci + '-' + fi + '" style="max-height:200px;overflow-y:auto;border:1px solid rgba(255,255,255,0.05);border-radius:8px;margin-top:0.25rem">' + sourceListHtml + '</div>' +
              '</div>' +
            '</div>' +
          '</div>'
          : '') +
        '</div>';
    });

    card.innerHTML = headerHtml + settingsHtml + foldersHtml +
      '<div style="padding:0.5rem 1rem 0.875rem"><button class="btn" onclick="addFolder(' + ci + ')" style="width:100%;padding:0.6rem;font-size:0.8rem">+ Add Folder</button></div>';

    container.appendChild(card);
  });
  updateSaveButtonState();
}

async function saveChanges() {
  var saveBtn = document.getElementById('saveBtn');
  saveBtn.disabled = true;
  saveBtn._polling = true;
  try {
    var res = await fetchWithTimeout('/api/collections', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ collections: collections })
    }, 8000);
    var data = await res.json();

    if (data.status === 'pending_confirmation') {
      showPendingStatus();
      pollStatus(data.id);
    } else if (data.error) {
      showErrorStatus(data.error);
      saveBtn.disabled = false;
      saveBtn._polling = false;
    }
  } catch (e) {
    showErrorStatus('${context.getString(R.string.web_error_failed_save).replace("'", "\\'")}');
    saveBtn.disabled = false;
    saveBtn._polling = false;
  }
}

function showPendingStatus() {
  var overlay = document.getElementById('statusOverlay');
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="spinner"></div></div>' +
    '<div class="status-title">${context.getString(R.string.web_status_waiting_tv).replace("'", "\\'")}</div>' +
    '<div class="status-message">${context.getString(R.string.web_status_msg_waiting_tv).replace("'", "\\'")}</div>';
  content.className = 'status-content';
  overlay.classList.add('visible');
}

function showSuccessStatus() {
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="status-svg"><svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg></div></div>' +
    '<div class="status-title">${context.getString(R.string.web_status_changes_applied).replace("'", "\\'")}</div>' +
    '<div class="status-message">Your collections have been updated on the TV.</div>';
  content.className = 'status-content status-success';
  setTimeout(dismissStatus, 2500);
}

function showRejectedStatus() {
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="status-svg"><svg viewBox="0 0 24 24" fill="none" stroke="rgba(207,102,121,0.9)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6L6 18M6 6l12 12"/></svg></div></div>' +
    '<div class="status-title">${context.getString(R.string.web_status_changes_rejected).replace("'", "\\'")}</div>' +
    '<div class="status-message">${context.getString(R.string.web_status_msg_changes_rejected).replace("'", "\\'")}</div>';
  content.className = 'status-content status-rejected';
  setTimeout(function() {
    collections = JSON.parse(JSON.stringify(originalCollections));
    renderCollections();
    dismissStatus();
  }, 2500);
}

function showErrorStatus(msg) {
  var overlay = document.getElementById('statusOverlay');
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="status-svg"><svg viewBox="0 0 24 24" fill="none" stroke="rgba(207,102,121,0.9)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 8v4M12 16h.01"/></svg></div></div>' +
    '<div class="status-title">${context.getString(R.string.web_status_error).replace("'", "\\'")}</div>' +
    '<div class="status-message">' + escapeHtml(msg) + '</div>' +
    '<div class="status-dismiss"><button class="btn" onclick="dismissStatus()">${context.getString(R.string.web_btn_dismiss).replace("'", "\\'")}</button></div>';
  content.className = 'status-content status-error';
  overlay.classList.add('visible');
}

function showTimeoutStatus() {
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="status-svg"><svg viewBox="0 0 24 24" fill="none" stroke="rgba(207,102,121,0.9)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg></div></div>' +
    '<div class="status-title">${context.getString(R.string.web_status_timeout).replace("'", "\\'")}</div>' +
    '<div class="status-message">${context.getString(R.string.web_status_msg_timeout).replace("'", "\\'")}</div>' +
    '<div class="status-dismiss"><button class="btn" onclick="dismissStatus()">${context.getString(R.string.web_btn_dismiss).replace("'", "\\'")}</button></div>';
  content.className = 'status-content status-error';
}

function showDisconnectedStatus() {
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="status-svg"><svg viewBox="0 0 24 24" fill="none" stroke="rgba(207,102,121,0.9)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 1l22 22M16.72 11.06A10.94 10.94 0 0 1 19 12.55M5 12.55a10.94 10.94 0 0 1 5.17-2.39M10.71 5.05A16 16 0 0 1 22.56 9M1.42 9a15.91 15.91 0 0 1 4.7-2.88M8.53 16.11a6 6 0 0 1 6.95 0M12 20h.01"/></svg></div></div>' +
    '<div class="status-title">${context.getString(R.string.web_connection_lost).replace("'", "\\'")}</div>' +
    '<div class="status-message">${context.getString(R.string.web_status_msg_connection_lost).replace("'", "\\'")}</div>' +
    '<div class="status-dismiss"><button class="btn" onclick="dismissStatus()">${context.getString(R.string.web_btn_dismiss).replace("'", "\\'")}</button></div>';
  content.className = 'status-content status-error';
}

function dismissStatus() {
  var overlay = document.getElementById('statusOverlay');
  overlay.classList.remove('visible');
  var saveBtn = document.getElementById('saveBtn');
  saveBtn.disabled = false;
  saveBtn._polling = false;
  if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
}

async function pollStatus(changeId) {
  pollStartTime = Date.now();
  consecutiveErrors = 0;

  var poll = async function() {
    if (Date.now() - pollStartTime > POLL_TIMEOUT) {
      showTimeoutStatus();
      document.getElementById('saveBtn').disabled = false;
      document.getElementById('saveBtn')._polling = false;
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
          document.getElementById('saveBtn')._polling = false;
        }, 2000);
      } else if (data.status === 'rejected') {
        showRejectedStatus();
        document.getElementById('saveBtn')._polling = false;
      } else if (data.status === 'not_found') {
        showDisconnectedStatus();
        document.getElementById('saveBtn').disabled = false;
        document.getElementById('saveBtn')._polling = false;
      } else {
        pollTimer = setTimeout(poll, POLL_INTERVAL);
      }
    } catch (e) {
      consecutiveErrors++;
      if (consecutiveErrors >= 3) {
        showDisconnectedStatus();
        document.getElementById('saveBtn').disabled = false;
        document.getElementById('saveBtn')._polling = false;
      } else {
        pollTimer = setTimeout(poll, 2000);
      }
    }
  };
  poll();
}

function exportCollections() {
  if (collections.length === 0) return;
  var json = JSON.stringify(collections, null, 2);
  var blob = new Blob([json], { type: 'application/json' });
  var url = URL.createObjectURL(blob);
  var a = document.createElement('a');
  a.href = url;
  a.download = 'nuvio-collections.json';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

var activeImportTab = 'paste';
var importFileContent = null;

function showImportModal() {
  document.getElementById('importOverlay').classList.add('visible');
  document.getElementById('importJsonInput').value = '';
  document.getElementById('importUrlInput').value = '';
  document.getElementById('importFileInput').value = '';
  document.getElementById('fileSelectedName').style.display = 'none';
  importFileContent = null;
  document.getElementById('importError').style.display = 'none';
  document.getElementById('importSuccess').style.display = 'none';
}

function dismissImportModal() {
  document.getElementById('importOverlay').classList.remove('visible');
}

function switchImportTab(tab) {
  activeImportTab = tab;
  document.querySelectorAll('.import-tab-btn').forEach(function(b, i) {
    b.classList.toggle('active', ['paste','file','url'][i] === tab);
  });
  document.querySelectorAll('.import-tab').forEach(function(t) { t.classList.remove('active'); });
  document.getElementById('import-tab-' + tab).classList.add('active');
}

function onFileSelected(input) {
  var file = input.files[0];
  if (!file) return;
  document.getElementById('fileSelectedName').textContent = file.name;
  document.getElementById('fileSelectedName').style.display = 'block';
  var reader = new FileReader();
  reader.onload = function(e) { importFileContent = e.target.result; };
  reader.readAsText(file);
}

async function doImport() {
  var errEl = document.getElementById('importError');
  var sucEl = document.getElementById('importSuccess');
  errEl.style.display = 'none';
  sucEl.style.display = 'none';

  var json = '';
  if (activeImportTab === 'paste') {
    json = document.getElementById('importJsonInput').value.trim();
  } else if (activeImportTab === 'file') {
    if (!importFileContent) { errEl.textContent = 'Select a file first'; errEl.style.display = 'block'; return; }
    json = importFileContent.trim();
  } else {
    var url = document.getElementById('importUrlInput').value.trim();
    if (!url) { errEl.textContent = 'Enter a URL'; errEl.style.display = 'block'; return; }
    try {
      var res = await fetch(url);
      json = await res.text();
    } catch (e) {
      errEl.textContent = 'Failed to fetch URL: ' + e.message;
      errEl.style.display = 'block';
      return;
    }
  }

  if (!json) { errEl.textContent = 'No JSON provided'; errEl.style.display = 'block'; return; }

  try {
    var parsed = JSON.parse(json);
    if (!Array.isArray(parsed)) { errEl.textContent = 'Expected a JSON array of collections'; errEl.style.display = 'block'; return; }
    if (parsed.length === 0) { errEl.textContent = 'Empty array: no collections found'; errEl.style.display = 'block'; return; }
    var validShapes = ['POSTER','LANDSCAPE','SQUARE','poster','wide','square'];
    for (var i = 0; i < parsed.length; i++) {
      var c = parsed[i];
      if (!c.id || typeof c.id !== 'string') { errEl.textContent = 'Collection ' + (i+1) + ': missing or invalid "id"'; errEl.style.display = 'block'; return; }
      if (!c.title || typeof c.title !== 'string') { errEl.textContent = 'Collection "' + (c.id) + '": missing or invalid "title"'; errEl.style.display = 'block'; return; }
      if (!Array.isArray(c.folders)) { errEl.textContent = 'Collection "' + c.title + '": "folders" must be an array'; errEl.style.display = 'block'; return; }
      for (var j = 0; j < c.folders.length; j++) {
        var f = c.folders[j];
        if (!f || typeof f !== 'object') { errEl.textContent = 'Collection "' + c.title + '", folder ' + (j+1) + ': invalid format'; errEl.style.display = 'block'; return; }
        if (!f.id || typeof f.id !== 'string') { errEl.textContent = 'Collection "' + c.title + '", folder ' + (j+1) + ': missing "id"'; errEl.style.display = 'block'; return; }
        if (!f.title || typeof f.title !== 'string') { errEl.textContent = 'Collection "' + c.title + '", folder "' + f.id + '": missing "title"'; errEl.style.display = 'block'; return; }
        if (!Array.isArray(f.catalogSources)) { errEl.textContent = 'Collection "' + c.title + '", folder "' + f.title + '": "catalogSources" must be an array'; errEl.style.display = 'block'; return; }
        if (f.tileShape && validShapes.indexOf(f.tileShape) < 0) { errEl.textContent = 'Collection "' + c.title + '", folder "' + f.title + '": invalid tileShape "' + f.tileShape + '"'; errEl.style.display = 'block'; return; }
        for (var k = 0; k < f.catalogSources.length; k++) {
          var s = f.catalogSources[k];
          if (!s || typeof s !== 'object') { errEl.textContent = 'Collection "' + c.title + '", folder "' + f.title + '", source ' + (k+1) + ': invalid format'; errEl.style.display = 'block'; return; }
          if (typeof s.addonId !== 'string' || typeof s.type !== 'string' || typeof s.catalogId !== 'string') { errEl.textContent = 'Collection "' + c.title + '", folder "' + f.title + '", source ' + (k+1) + ': missing required fields'; errEl.style.display = 'block'; return; }
        }
      }
    }
    var existingById = {};
    collections.forEach(function(c, idx) { existingById[c.id] = idx; });
    parsed.forEach(function(imported) {
      if (imported.id in existingById) {
        collections[existingById[imported.id]] = imported;
      } else {
        collections.push(imported);
      }
    });
    renderCollections();
    sucEl.textContent = 'Imported ' + parsed.length + ' collection(s). Review and hit Save Changes to apply.';
    sucEl.style.display = 'block';
    setTimeout(function() { dismissImportModal(); }, 2000);
  } catch (e) {
    errEl.textContent = 'Invalid JSON: ' + e.message;
    errEl.style.display = 'block';
  }
}

loadState();
</script>
</body>
</html>
""".trimIndent()
    }
}
