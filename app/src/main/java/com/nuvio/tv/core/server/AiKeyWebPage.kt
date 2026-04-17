package com.nuvio.tv.core.server

object AiKeyWebPage {

    private val sharedCss = """
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap" rel="stylesheet">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  *:focus, *:active { outline: none !important; }
  body {
    font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: #000; color: #fff; min-height: 100vh; line-height: 1.5;
  }
  .page { max-width: 500px; margin: 0 auto; padding: 0 1.5rem 6rem; }
  .header { text-align: center; padding: 3rem 0 2.5rem; border-bottom: 1px solid rgba(255,255,255,0.05); margin-bottom: 2.5rem; }
  .header-logo { height: 40px; width: auto; margin-bottom: 0.5rem; filter: brightness(0) invert(1); opacity: 0.9; }
  .header p { font-size: 0.875rem; font-weight: 300; color: rgba(255,255,255,0.4); }
  label { display: block; font-size: 0.75rem; font-weight: 500; color: rgba(255,255,255,0.3); letter-spacing: 0.1em; text-transform: uppercase; margin-bottom: 0.75rem; }
  input[type=text], input[type=password] {
    width: 100%; background: transparent; border: 1px solid rgba(255,255,255,0.12);
    border-radius: 100px; padding: 0.875rem 1.25rem; color: #fff;
    font-family: inherit; font-size: 0.9rem; transition: border-color 0.3s ease; margin-bottom: 0.75rem;
  }
  input:focus { border-color: rgba(255,255,255,0.4); }
  input::placeholder { color: rgba(255,255,255,0.2); }
  .show-toggle { font-size: 0.8rem; color: rgba(255,255,255,0.3); cursor: pointer; display: inline-flex; align-items: center; gap: 0.4rem; margin-bottom: 1.5rem; user-select: none; }
  .show-toggle:hover { color: rgba(255,255,255,0.6); }
  .hint { font-size: 0.8rem; color: rgba(255,255,255,0.25); margin-bottom: 2rem; line-height: 1.6; }
  .hint a { color: rgba(255,255,255,0.45); }
  .btn { display: inline-flex; align-items: center; justify-content: center; width: 100%; background: transparent; border: 1px solid rgba(255,255,255,0.2); border-radius: 100px; padding: 1rem; color: #fff; font-family: inherit; font-size: 0.95rem; font-weight: 600; cursor: pointer; transition: all 0.3s ease; text-decoration: none; }
  .btn:hover { background: #fff; color: #000; border-color: #fff; }
  .btn:active { transform: scale(0.97); }
  .btn:disabled { opacity: 0.2; cursor: not-allowed; pointer-events: none; }
  .status { margin-top: 1.5rem; padding: 1rem 1.25rem; border-radius: 12px; font-size: 0.875rem; font-weight: 500; text-align: center; display: none; }
  .status.success { background: rgba(255,255,255,0.08); color: #fff; display: block; }
  .status.error { background: rgba(207,102,121,0.12); color: rgba(207,102,121,0.9); display: block; }
  .back { display: inline-flex; align-items: center; gap: 0.5rem; font-size: 0.8rem; color: rgba(255,255,255,0.3); text-decoration: none; margin-bottom: 1.5rem; cursor: pointer; }
  .back:hover { color: rgba(255,255,255,0.6); }
</style>
""".trimIndent()

    fun getLandingHtml(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<title>NuvioTV – AI Translation Key</title>
$sharedCss
<style>
  .provider-btn { display: flex; flex-direction: column; align-items: flex-start; width: 100%; background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.1); border-radius: 16px; padding: 1.25rem 1.5rem; color: #fff; font-family: inherit; cursor: pointer; transition: all 0.25s ease; text-decoration: none; margin-bottom: 1rem; }
  .provider-btn:hover { background: rgba(255,255,255,0.08); border-color: rgba(255,255,255,0.25); }
  .provider-btn .name { font-size: 1rem; font-weight: 600; margin-bottom: 0.25rem; }
  .provider-btn .model { font-size: 0.8rem; color: rgba(255,255,255,0.4); }
  .provider-btn .badge { font-size: 0.7rem; font-weight: 600; letter-spacing: 0.05em; padding: 0.2rem 0.6rem; border-radius: 100px; margin-bottom: 0.75rem; display: inline-block; }
  .badge-groq { background: rgba(100,200,160,0.15); color: #7ec8a0; }
  .badge-gemini { background: rgba(66,133,244,0.15); color: #6aabff; }
</style>
</head>
<body>
<div class="page">
  <div class="header">
    <img src="/logo.png" alt="NuvioTV" class="header-logo">
    <p>AI Subtitle Translation – API Key Setup</p>
  </div>

  <p style="font-size:0.85rem; color:rgba(255,255,255,0.35); margin-bottom:1.5rem;">Choose your AI provider to set its API key:</p>

  <a href="/groq" class="provider-btn">
    <span class="badge badge-groq">DEFAULT</span>
    <span class="name">Groq</span>
    <span class="model">Llama 3.3 70B · Free tier: 1K RPM / 500K RPD</span>
  </a>

  <a href="/gemini" class="provider-btn">
    <span class="badge badge-gemini">BETTER QUALITY</span>
    <span class="name">Google Gemini</span>
    <span class="model">Gemini 2.5 Flash · Free tier available</span>
  </a>
</div>
</body>
</html>
""".trimIndent()

    fun getGroqHtml(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<title>NuvioTV – Groq API Key</title>
$sharedCss
</head>
<body>
<div class="page">
  <div class="header">
    <img src="/logo.png" alt="NuvioTV" class="header-logo">
    <p>Set your Groq API Key</p>
  </div>

  <a href="/" class="back">← Back</a>

  <label for="apiKey">Groq API Key</label>
  <input type="password" id="apiKey" placeholder="gsk_..." autocomplete="off" autocapitalize="off" spellcheck="false">
  <div class="show-toggle" onclick="toggleShow()">
    <svg id="eyeIcon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
    <span id="showLabel">Show key</span>
  </div>

  <p class="hint">
    Get your free key at <a href="https://console.groq.com/keys" target="_blank">console.groq.com/keys</a><br>
    Free tier: 1,000 requests/min · 500,000 requests/day
  </p>

  <button class="btn" id="saveBtn" onclick="saveKey()">Save to TV</button>
  <div class="status" id="status"></div>
</div>

<script>
var showing = false;
function toggleShow() {
  showing = !showing;
  document.getElementById('apiKey').type = showing ? 'text' : 'password';
  document.getElementById('showLabel').textContent = showing ? 'Hide key' : 'Show key';
}
async function saveKey() {
  var key = document.getElementById('apiKey').value.trim();
  var btn = document.getElementById('saveBtn');
  var status = document.getElementById('status');
  btn.disabled = true;
  status.className = 'status';
  try {
    var res = await fetch('/api/key', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({key: key}) });
    var data = await res.json();
    if (data.status === 'saved') {
      status.className = 'status success';
      status.textContent = key ? 'Groq API key saved to TV.' : 'Key cleared.';
    } else { throw new Error(); }
  } catch(e) {
    status.className = 'status error';
    status.textContent = 'Failed to save. Make sure your phone and TV are on the same network.';
  }
  btn.disabled = false;
}
document.getElementById('apiKey').addEventListener('keydown', function(e) { if (e.key === 'Enter') saveKey(); });
</script>
</body>
</html>
""".trimIndent()

    fun getGeminiHtml(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<title>NuvioTV – Gemini API Key</title>
$sharedCss
</head>
<body>
<div class="page">
  <div class="header">
    <img src="/logo.png" alt="NuvioTV" class="header-logo">
    <p>Set your Google Gemini API Key</p>
  </div>

  <a href="/" class="back">← Back</a>

  <label for="apiKey">Google Gemini API Key</label>
  <input type="password" id="apiKey" placeholder="AIzaSy..." autocomplete="off" autocapitalize="off" spellcheck="false">
  <div class="show-toggle" onclick="toggleShow()">
    <svg id="eyeIcon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
    <span id="showLabel">Show key</span>
  </div>

  <p class="hint">
    Get your free key at <a href="https://aistudio.google.com/apikey" target="_blank">aistudio.google.com/apikey</a>
  </p>

  <button class="btn" id="saveBtn" onclick="saveKey()">Save to TV</button>
  <div class="status" id="status"></div>
</div>

<script>
var showing = false;
function toggleShow() {
  showing = !showing;
  document.getElementById('apiKey').type = showing ? 'text' : 'password';
  document.getElementById('showLabel').textContent = showing ? 'Hide key' : 'Show key';
}
async function saveKey() {
  var key = document.getElementById('apiKey').value.trim();
  var btn = document.getElementById('saveBtn');
  var status = document.getElementById('status');
  btn.disabled = true;
  status.className = 'status';
  try {
    var res = await fetch('/api/key', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({key: key}) });
    var data = await res.json();
    if (data.status === 'saved') {
      status.className = 'status success';
      status.textContent = key ? 'Gemini API key saved to TV.' : 'Key cleared.';
    } else { throw new Error(); }
  } catch(e) {
    status.className = 'status error';
    status.textContent = 'Failed to save. Make sure your phone and TV are on the same network.';
  }
  btn.disabled = false;
}
document.getElementById('apiKey').addEventListener('keydown', function(e) { if (e.key === 'Enter') saveKey(); });
</script>
</body>
</html>
""".trimIndent()
}
