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
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>$appName - $title</title>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&display=swap" rel="stylesheet">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body { font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #000; color: #fff; min-height: 100vh; line-height: 1.5; }
  .page { max-width: 600px; margin: 0 auto; padding: 0 1.5rem 6rem; }
  .header { text-align: center; padding: 3rem 0 2.5rem; border-bottom: 1px solid rgba(255,255,255,0.06); margin-bottom: 2.5rem; }
  .header-logo { height: 40px; width: auto; margin-bottom: 0.5rem; filter: brightness(0) invert(1); opacity: 0.9; }
  .header p { font-size: 0.875rem; font-weight: 300; color: rgba(255,255,255,0.42); letter-spacing: 0.02em; }
  .intro { margin-bottom: 2rem; }
  .intro-copy { color: rgba(255,255,255,0.44); font-size: 0.875rem; font-weight: 300; }
  .actions { display: flex; gap: 0.75rem; margin-top: 1.25rem; }
  .btn { display: inline-flex; align-items: center; justify-content: center; gap: 0.5rem; flex: 1; background: transparent; border: 1px solid rgba(255,255,255,0.2); border-radius: 100px; padding: 0.875rem 1.3rem; color: #fff; font-family: inherit; font-size: 0.875rem; font-weight: 500; cursor: pointer; transition: all 0.25s ease; white-space: nowrap; }
  .btn:hover { background: #fff; color: #000; border-color: #fff; }
  input[type=file] { display: none; }
  .status { color: rgba(135,239,172,0.95); font-size: 0.875rem; font-weight: 300; min-height: 24px; margin-top: 1rem; text-align: center; }
  .status.error { color: rgba(207,102,121,0.9); }
  @media (max-width: 480px) { .page { padding: 0 1rem 5rem; } .header { padding: 2rem 0; } .header-logo { height: 32px; } }
</style>
</head>
<body>
<div class="page">
  <div class="header">
    <img src="/logo.png" alt="$appName" class="header-logo">
    <p>$title</p>
  </div>
  <div class="intro">
    <div class="intro-copy">$subtitle</div>
  </div>
  <div class="actions">
    <label class="btn" for="font">$choose</label>
  </div>
  <input id="font" type="file" accept=".ttf,.otf">
  <div class="status" id="status"></div>
</div>
<script>
const input=document.getElementById('font');
const statusBox=document.getElementById('status');
function show(text,failed){statusBox.textContent=text;statusBox.className=failed?'status error':'status'}
input.addEventListener('change',async()=>{
  const file=input.files[0];
  if(!file)return;
  show($uploading,false);
  try{
    const response=await fetch('/api/font',{method:'POST',
      headers:{'Content-Type':'application/octet-stream','X-File-Name':encodeURIComponent(file.name)},body:file});
    if(response.ok){show($added,false)}else{show($error,true)}
  }catch(e){show($connectionError,true)}
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
