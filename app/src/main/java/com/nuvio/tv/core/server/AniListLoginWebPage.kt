package com.nuvio.tv.core.server

internal object AniListLoginWebPage {

    fun html(authorizeUrl: String): String {
        val safeUrl = authorizeUrl.replace("&", "&amp;").replace("\"", "&quot;")
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>NuvioTV - Connect AniList</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
body{font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
background:#0b1622;color:#fff;display:flex;justify-content:center;align-items:center;min-height:100vh;padding:16px}
.card{max-width:520px;width:100%;padding:28px 0}
.brand{display:flex;align-items:center;gap:12px;margin-bottom:20px}
.brand svg{width:40px;height:40px}
h1{font-size:20px;font-weight:600}
.subtitle{font-size:13px;color:rgba(255,255,255,0.55);margin-top:2px}
.step{margin-top:22px}
.step-title{font-size:0.75rem;font-weight:600;color:#3db4f2;letter-spacing:0.1em;text-transform:uppercase;margin-bottom:8px}
.step p{font-size:14px;color:rgba(255,255,255,0.75);line-height:1.5;margin-bottom:12px}
a.button,button{display:block;width:100%;text-align:center;padding:0.9rem 1.3rem;border-radius:100px;
font-family:inherit;font-size:0.95rem;font-weight:600;cursor:pointer;text-decoration:none;transition:opacity .2s}
a.button{background:#3db4f2;color:#0b1622;border:none}
button{background:transparent;color:#fff;border:1px solid rgba(255,255,255,0.25)}
button:disabled{opacity:.5}
a.button:active,button:active{opacity:.7}
textarea{width:100%;min-height:110px;padding:12px 1rem;border-radius:16px;border:1px solid rgba(255,255,255,0.16);
background:rgba(255,255,255,0.04);color:#fff;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:0.78rem;
line-height:1.45;resize:vertical;outline:none;margin-bottom:12px;word-break:break-all}
textarea:focus,.pair:focus{border-color:#3db4f2}
.pair{display:none;width:100%;padding:12px 1rem;border-radius:16px;border:1px solid rgba(255,255,255,0.16);
background:rgba(255,255,255,0.04);color:#fff;font-family:inherit;font-size:1rem;letter-spacing:0.3em;text-align:center;outline:none;margin-bottom:12px}
.pair::placeholder{letter-spacing:normal}
.status{margin-top:14px;font-size:14px;text-align:center;min-height:20px}
.status.ok{color:#4caf50}
.status.err{color:#ef5350}
.done{display:none;text-align:center;margin-top:28px}
.done h2{font-size:18px;margin-bottom:6px}
.done p{font-size:14px;color:rgba(255,255,255,0.65)}
</style>
</head>
<body>
<div class="card">
<div class="brand">
<svg viewBox="0 0 24 24"><path fill="#02A9FF" d="M6.361 2.943 0 21.056h4.942l1.077-3.133H11.4l1.052 3.133H22.9c.71 0 1.1-.392 1.1-1.101V17.53c0-.71-.39-1.101-1.1-1.101h-6.483V4.045c0-.71-.392-1.102-1.101-1.102h-2.422c-.71 0-1.101.392-1.101 1.102v1.064l-.758-2.166zm2.324 5.948 1.688 5.018H7.144z"/></svg>
<div><h1>Connect AniList</h1><div class="subtitle">Sign in on this device to link AniList to your TV.</div></div>
</div>
<div id="form">
<div class="step">
<div class="step-title">Step 1</div>
<p>Sign in to AniList and approve NuvioTV. AniList will then show you an access token.</p>
<a class="button" href="$safeUrl" target="_blank" rel="noopener">Sign in with AniList</a>
</div>
<div class="step">
<div class="step-title">Step 2</div>
<p>Copy the token from AniList, come back to this page and paste it below.</p>
<input id="pair" class="pair" placeholder="Code shown on your TV" inputmode="numeric" autocomplete="off" maxlength="6">
<textarea id="token" placeholder="Paste your AniList token here" autocomplete="off" autocapitalize="off" spellcheck="false"></textarea>
<button id="submit" onclick="submitToken()">Connect to TV</button>
<div class="status" id="status"></div>
</div>
</div>
<div class="done" id="done"><h2>You're connected</h2><p>AniList is now linked to NuvioTV. You can close this page.</p></div>
</div>
<script>
var code=new URLSearchParams(location.search).get('code')||'';
if(!code){document.getElementById('pair').style.display='block';}
function setStatus(text,kind){var s=document.getElementById('status');s.textContent=text;s.className='status'+(kind?' '+kind:'');}
function submitToken(){
var token=document.getElementById('token').value.trim();
if(!code){code=document.getElementById('pair').value.trim();}
if(!code){setStatus('Enter the code shown on your TV.','err');return;}
if(!token){setStatus('Paste the token shown by AniList.','err');return;}
var button=document.getElementById('submit');button.disabled=true;setStatus('Connecting…');
fetch('/api/token',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code:code,token:token})})
.then(function(r){return r.json();})
.then(function(r){
if(r.success){document.getElementById('form').style.display='none';document.getElementById('done').style.display='block';}
else{button.disabled=false;setStatus(r.message||'Connection failed.','err');}
})
.catch(function(){button.disabled=false;setStatus('Could not reach your TV. Make sure both devices are on the same network.','err');});
}
</script>
</body>
</html>
""".trimIndent()
    }
}
