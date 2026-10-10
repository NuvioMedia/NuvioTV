package com.nuvio.tv.core.server

import android.content.Context
import android.content.res.Configuration
import com.nuvio.tv.R
import java.util.Locale

object UsenetSourcesWebPage {
    /** The app's chosen language, which may differ from the system one. */
    fun localized(base: Context): Context {
        val tag = base.getSharedPreferences("app_locale", Context.MODE_PRIVATE).getString("locale_tag", null)
        if (tag.isNullOrEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }

    fun html(context: Context): String {
        fun s(id: Int) = context.getString(id).html()
        fun js(id: Int) = context.getString(id).js()
        val priorities = (1..5).joinToString("") { p ->
            val label = when (p) {
                1 -> context.getString(R.string.usenet_priority_highest, p)
                5 -> context.getString(R.string.usenet_priority_lowest, p)
                else -> p.toString()
            }
            """<option value="$p">${label.html()}</option>"""
        }
        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="referrer" content="no-referrer">
<title>${context.getString(R.string.app_name).html()} - ${s(R.string.usenet_sources_title)}</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #000; color: #fff; line-height: 1.5; }
  .page { max-width: 600px; margin: 0 auto; padding: 0 1.25rem 5rem; }
  .header { text-align: center; padding: 2.5rem 0 2rem; border-bottom: 1px solid rgba(255,255,255,0.05); margin-bottom: 2rem; }
  .header img { height: 36px; margin-bottom: 0.5rem; filter: brightness(0) invert(1); opacity: 0.9; }
  .header p { font-size: 0.875rem; font-weight: 300; color: rgba(255,255,255,0.4); }
  .intro { color: rgba(255,255,255,0.5); font-size: 0.875rem; font-weight: 300; margin-bottom: 2rem; }
  .tabs { display: grid; grid-template-columns: 1fr 1fr; gap: 0.5rem; margin-bottom: 1.5rem; }
  .tab { border: 1px solid rgba(255,255,255,0.12); border-radius: 999px; background: transparent; color: rgba(255,255,255,0.58);
    font: inherit; font-size: 0.875rem; font-weight: 600; padding: 0.8rem 1rem; }
  .tab.active { background: #fff; border-color: #fff; color: #000; }
  .item { display: block; width: 100%; text-align: left; background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.08);
    border-radius: 16px; padding: 0.9rem 1rem; margin-bottom: 0.6rem; color: #fff; font: inherit; }
  .item b { display: block; font-weight: 600; }
  .item span { color: rgba(255,255,255,0.45); font-size: 0.8rem; word-break: break-all; }
  .empty { color: rgba(255,255,255,0.35); font-size: 0.875rem; margin-bottom: 1rem; }
  form { display: none; margin-top: 1rem; }
  form.open { display: block; }
  .form-title { font-weight: 700; margin-bottom: 1rem; }
  .hint { color: rgba(255,255,255,0.42); font-size: 0.8rem; font-weight: 300; margin: -0.5rem 0 1rem; }
  label { display: block; font-size: 0.72rem; font-weight: 500; color: rgba(255,255,255,0.4); letter-spacing: 0.08em;
    text-transform: uppercase; margin: 0 0 0.4rem; }
  input, select { width: 100%; background: transparent; border: 1px solid rgba(255,255,255,0.15); border-radius: 12px;
    padding: 0.75rem 0.85rem; color: #fff; font: inherit; font-size: 1rem; margin-bottom: 1rem; }
  input:focus, select:focus { outline: none; border-color: rgba(255,255,255,0.5); }
  select option { color: #000; }
  .row { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem; }
  .check { display: flex; align-items: center; gap: 0.6rem; margin-bottom: 1rem; text-transform: none; letter-spacing: 0;
    font-size: 0.9rem; color: rgba(255,255,255,0.75); }
  .check input { width: auto; margin: 0; accent-color: #fff; }
  .actions { display: flex; gap: 0.6rem; margin-top: 0.5rem; }
  .btn { flex: 1; background: transparent; border: 1px solid rgba(255,255,255,0.25); border-radius: 100px; padding: 0.8rem 1rem;
    color: #fff; font: inherit; font-size: 0.875rem; font-weight: 500; }
  .btn.primary { background: #fff; color: #000; border-color: #fff; }
  .btn:disabled { opacity: 0.4; }
  .add { width: 100%; margin-top: 0.4rem; }
  .status { min-height: 1.5rem; margin-top: 1rem; font-size: 0.875rem; text-align: center; color: rgba(135,239,172,0.95); }
  .status.error { color: rgba(207,102,121,0.95); }
</style>
</head>
<body>
<div class="page">
  <div class="header">
    <img src="/logo.png" alt="" onerror="this.remove()">
    <p>${s(R.string.usenet_sources_title)}</p>
  </div>
  <div class="intro">${s(R.string.usenet_phone_intro)}</div>
  <div class="tabs">
    <button class="tab active" type="button" data-tab="providers">${s(R.string.usenet_phone_providers)}</button>
    <button class="tab" type="button" data-tab="indexers">${s(R.string.usenet_phone_indexers)}</button>
  </div>

  <div id="panel-providers">
    <div id="providerList"></div>
    <button class="btn add" type="button" id="addProvider">${s(R.string.usenet_add_provider)}</button>
    <form id="providerForm" autocomplete="off">
      <div class="form-title" id="providerTitle"></div>
      <label for="pName">${s(R.string.usenet_source_name)}</label>
      <input id="pName" required>
      <label for="pHost">${s(R.string.usenet_provider_host)}</label>
      <input id="pHost" autocapitalize="none" spellcheck="false" inputmode="url" required>
      <div class="row">
        <div><label for="pPort">${s(R.string.usenet_provider_port)}</label><input id="pPort" type="number" inputmode="numeric" min="1" max="65535"></div>
        <div><label for="pConnections">${s(R.string.usenet_provider_connections)}</label><input id="pConnections" type="number" inputmode="numeric" min="1" max="4096"></div>
      </div>
      <label class="check"><input type="checkbox" id="pTls"> ${s(R.string.usenet_provider_tls)}</label>
      <label for="pUser">${s(R.string.usenet_provider_username)}</label>
      <input id="pUser" autocapitalize="none" spellcheck="false">
      <label for="pPassword">${s(R.string.usenet_provider_password)}</label>
      <input id="pPassword" type="password" autocomplete="new-password">
      <label for="pPriority">${s(R.string.usenet_priority)}</label>
      <select id="pPriority">$priorities</select>
      <div class="hint">${s(R.string.usenet_provider_priority_hint)}</div>
      <div class="actions">
        <button class="btn" type="button" data-cancel>${s(R.string.action_cancel)}</button>
        <button class="btn" type="button" id="testProvider">${s(R.string.usenet_provider_test)}</button>
        <button class="btn primary" type="submit">${s(R.string.action_save)}</button>
      </div>
    </form>
  </div>

  <div id="panel-indexers" hidden>
    <div id="indexerList"></div>
    <button class="btn add" type="button" id="addIndexer">${s(R.string.usenet_add_indexer)}</button>
    <form id="indexerForm" autocomplete="off">
      <div class="form-title" id="indexerTitle"></div>
      <div class="hint" style="margin-top:0">${s(R.string.usenet_indexer_hint)}</div>
      <label for="iName">${s(R.string.usenet_source_name)}</label>
      <input id="iName" required>
      <label for="iUrl">${s(R.string.usenet_indexer_url)}</label>
      <input id="iUrl" type="url" autocapitalize="none" spellcheck="false" placeholder="https://indexer.example/api" required>
      <label for="iKey">${s(R.string.usenet_indexer_key)}</label>
      <input id="iKey" type="password" autocomplete="new-password" autocapitalize="none" spellcheck="false">
      <label for="iPriority">${s(R.string.usenet_priority)}</label>
      <select id="iPriority">$priorities</select>
      <div class="hint">${s(R.string.usenet_indexer_priority_hint)}</div>
      <div class="actions">
        <button class="btn" type="button" data-cancel>${s(R.string.action_cancel)}</button>
        <button class="btn" type="button" id="testIndexer">${s(R.string.usenet_test_indexer)}</button>
        <button class="btn primary" type="submit">${s(R.string.action_save)}</button>
      </div>
    </form>
  </div>

  <div class="status" id="status"></div>
</div>
<script>
// Keep the token out of the address bar, history and any shared screenshot; the tab still survives a reload.
let token = new URLSearchParams(location.search).get('t');
try {
  if (token) sessionStorage.setItem('t', token); else token = sessionStorage.getItem('t');
  history.replaceState(null, '', '/');
} catch (e) {}
token = token || '';
const $ = id => document.getElementById(id);
const statusBox = $('status');
let sources = { providers: [], indexers: [] };
let editing = { provider: null, indexer: null };

function setStatus(text, error) {
  statusBox.textContent = text || '';
  statusBox.className = error ? 'status error' : 'status';
  // On a phone the status sits below the form's buttons, often off screen.
  if (text) statusBox.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}
function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
}
async function api(path, body) {
  const res = await fetch(path, {
    method: body ? 'POST' : 'GET',
    headers: Object.assign({ '${UsenetSourcesConfigServer.TOKEN_HEADER}': token }, body ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
    body: body ? JSON.stringify(body) : undefined
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || '${js(R.string.usenet_phone_unavailable)}');
  return data;
}
function priorityText(p) { return '${js(R.string.usenet_priority_value)}'.replace('%1${'$'}d', p); }
function state(enabled) { return enabled ? '${js(R.string.usenet_source_enabled)}' : '${js(R.string.usenet_source_disabled)}'; }
function render() {
  $('providerList').innerHTML = sources.providers.length ? sources.providers.map((p, i) =>
    '<button class="item" type="button" data-provider="' + i + '"><b>' + escapeHtml(p.name) + '</b><span>' +
    escapeHtml(p.host + ':' + p.port) + ' • ' + p.connections + ' • ' + priorityText(p.priority) + ' • ' + state(p.enabled) +
    '</span></button>').join('') : '<div class="empty">${js(R.string.usenet_phone_none)}</div>';
  $('indexerList').innerHTML = sources.indexers.length ? sources.indexers.map((x, i) =>
    '<button class="item" type="button" data-indexer="' + i + '"><b>' + escapeHtml(x.name) + '</b><span>' +
    escapeHtml(x.apiUrl.split('?')[0]) + ' • ' + priorityText(x.priority) + ' • ' + state(x.enabled) +
    '</span></button>').join('') : '<div class="empty">${js(R.string.usenet_phone_none)}</div>';
  document.querySelectorAll('[data-provider]').forEach(b => b.onclick = () => openProvider(sources.providers[b.dataset.provider]));
  document.querySelectorAll('[data-indexer]').forEach(b => b.onclick = () => openIndexer(sources.indexers[b.dataset.indexer]));
}
async function load() {
  sources = await api('/api/sources');
  render();
}
function secretHint(input, saved) {
  input.value = '';
  input.placeholder = saved ? '${js(R.string.usenet_phone_keep_secret)}' : '';
}
function openProvider(p) {
  editing.provider = p ? p.id : null;
  $('providerTitle').textContent = p ? '${js(R.string.usenet_edit_provider)}' : '${js(R.string.usenet_add_provider)}';
  $('pName').value = p ? p.name : '';
  $('pHost').value = p ? p.host : '';
  $('pPort').value = p ? p.port : 563;
  $('pConnections').value = p ? p.connections : 20;
  $('pTls').checked = p ? p.tls : true;
  $('pUser').value = p ? p.username : '';
  secretHint($('pPassword'), p && p.hasPassword);
  $('pPriority').value = p ? p.priority : 1;
  $('providerForm').classList.add('open');
  setStatus('');
  $('pName').focus();
}
function openIndexer(x) {
  editing.indexer = x ? x.id : null;
  $('indexerTitle').textContent = x ? '${js(R.string.usenet_edit_indexer)}' : '${js(R.string.usenet_add_indexer)}';
  $('iName').value = x ? x.name : '';
  $('iUrl').value = x ? x.apiUrl : '';
  secretHint($('iKey'), x && x.hasKey);
  $('iPriority').value = x ? x.priority : 1;
  $('indexerForm').classList.add('open');
  setStatus('');
  $('iName').focus();
}
function providerValue() {
  return { id: editing.provider, name: $('pName').value, host: $('pHost').value, port: parseInt($('pPort').value, 10) || 0,
    connections: parseInt($('pConnections').value, 10) || 0, tls: $('pTls').checked, username: $('pUser').value,
    password: $('pPassword').value, priority: parseInt($('pPriority').value, 10) };
}
function indexerValue() {
  return { id: editing.indexer, name: $('iName').value, apiUrl: $('iUrl').value, apiKey: $('iKey').value,
    priority: parseInt($('iPriority').value, 10) };
}
async function run(button, pending, action) {
  button.disabled = true;
  setStatus(pending);
  try { await action(); }
  catch (e) { setStatus(e.message, true); }
  finally { button.disabled = false; }
}
$('pTls').onchange = () => {
  const port = $('pPort');
  if (port.value === '119' || port.value === '563') port.value = $('pTls').checked ? 563 : 119;
};
$('testProvider').onclick = e => run(e.target, '${js(R.string.usenet_provider_test_running)}', async () => {
  const r = await api('/api/providers/test', providerValue());
  setStatus(r.message, !r.ok);
});
$('testIndexer').onclick = e => run(e.target, '${js(R.string.usenet_test_running)}', async () => {
  const r = await api('/api/indexers/test', indexerValue());
  setStatus(r.message, !r.ok);
});
$('providerForm').onsubmit = e => {
  e.preventDefault();
  run(e.submitter || e.target, '${js(R.string.web_status_saving)}', async () => {
    const r = await api('/api/providers', providerValue());
    $('providerForm').classList.remove('open');
    await load();
    setStatus(r.message);
  });
};
$('indexerForm').onsubmit = e => {
  e.preventDefault();
  run(e.submitter || e.target, '${js(R.string.web_status_saving)}', async () => {
    const r = await api('/api/indexers', indexerValue());
    $('indexerForm').classList.remove('open');
    await load();
    setStatus(r.message);
  });
};
document.querySelectorAll('[data-cancel]').forEach(b => b.onclick = () => { b.closest('form').classList.remove('open'); setStatus(''); });
$('addProvider').onclick = () => openProvider(null);
$('addIndexer').onclick = () => openIndexer(null);
document.querySelectorAll('.tab').forEach(tab => tab.onclick = () => {
  document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t === tab));
  $('panel-providers').hidden = tab.dataset.tab !== 'providers';
  $('panel-indexers').hidden = tab.dataset.tab !== 'indexers';
  setStatus('');
});
load().catch(e => setStatus(e.message, true));
</script>
</body>
</html>
""".trimIndent()
    }

    private fun String.html(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun String.js(): String =
        replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("<", "\\x3c")
}
