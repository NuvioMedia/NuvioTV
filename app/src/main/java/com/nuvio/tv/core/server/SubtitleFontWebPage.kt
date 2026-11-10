package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.util.withAppLocale

internal object SubtitleFontWebPage {

    fun html(baseContext: Context): String {
        val context = baseContext.withAppLocale()
        val appName = context.getString(R.string.app_name).html()
        val title = context.getString(R.string.web_subtitle_font_title).html()
        val subtitle = context.getString(R.string.web_subtitle_font_subtitle).html()
        val choose = context.getString(R.string.web_subtitle_font_choose).html()
        val uploading = context.getString(R.string.web_subtitle_font_uploading).js()
        val added = context.getString(R.string.web_subtitle_font_added).js()
        val error = context.getString(R.string.web_subtitle_font_error).js()
        val connectionError = context.getString(R.string.web_subtitle_font_connection_error).js()

        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$appName - $title</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
body{font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
background:#000;color:#fff;display:flex;justify-content:center;align-items:center;min-height:100vh;padding:16px}
.card{max-width:560px;width:100%;padding:32px 0}
h1{font-size:20px;font-weight:600;margin-bottom:4px}
.subtitle{font-size:13px;color:rgba(255,255,255,0.5);margin-bottom:24px;line-height:1.5}
label{display:block;width:100%;padding:0.875rem 1.3rem;border:1px solid rgba(255,255,255,0.2);border-radius:100px;
text-align:center;font-size:0.875rem;font-weight:500;cursor:pointer;transition:all .25s ease}
label:hover{background:#fff;color:#000;border-color:#fff}
input{display:none}
.status{margin-top:16px;font-size:13px;text-align:center;min-height:20px;color:rgba(255,255,255,0.6)}
.status.ok{color:#4caf50}
.status.err{color:#ef5350}
</style>
</head>
<body>
<div class="card">
  <h1>$title</h1>
  <p class="subtitle">$subtitle</p>
  <label for="font">$choose</label>
  <input id="font" type="file" accept=".ttf,.otf">
  <div id="status" class="status"></div>
</div>
<script>
const input=document.getElementById('font');
const statusView=document.getElementById('status');
function show(text,kind){statusView.textContent=text;statusView.className='status '+kind}
input.addEventListener('change',async()=>{
  const file=input.files[0];
  if(!file)return;
  show($uploading,'');
  try{
    const response=await fetch('/api/font',{method:'POST',
      headers:{'Content-Type':'application/octet-stream','X-File-Name':encodeURIComponent(file.name)},body:file});
    if(response.ok){show($added,'ok')}else{show($error,'err')}
  }catch(e){show($connectionError,'err')}
  input.value='';
});
</script>
</body>
</html>
""".trimIndent()
    }

    private fun String.html(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun String.js(): String = buildString {
        append('"')
        this@js.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '<' -> append("\\u003c")
                else -> append(char)
            }
        }
        append('"')
    }
}
