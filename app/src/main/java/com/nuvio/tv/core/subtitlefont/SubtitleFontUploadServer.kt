package com.nuvio.tv.core.subtitlefont

import android.content.Context
import com.nuvio.tv.R
import fi.iki.elonen.NanoHTTPD
import java.security.SecureRandom

/**
 * Local web page for sending a subtitle font from a phone or computer to the TV: many Android TV
 * devices have no usable document picker. The page posts the raw font file to `/<token>/api/font`.
 * Every path carries a random per-session [token] (it is in the QR code), so only someone who
 * can see the TV screen can reach the page or upload.
 */
internal class SubtitleFontUploadServer(
    private val context: Context,
    private val onImported: (SubtitleFontImportResult) -> Unit,
    port: Int,
) : NanoHTTPD(port) {

    val token: String = newToken()
    private val pagePath = "/$token/"
    private val fontPath = "/$token/api/font"

    override fun serve(session: IHTTPSession): Response = when {
        session.method == Method.GET && (session.uri == pagePath || session.uri == "/$token") ->
            newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                SubtitleFontUploadWebPage.html(context, fontPath),
            )
        session.method == Method.GET && session.uri == fontPath -> json(
            Response.Status.OK,
            """{"font":${jsonString(SubtitleFontStore.current(context)?.familyName)}}""",
        )
        session.method == Method.POST && session.uri == fontPath -> handleUpload(session)
        else -> newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
            .apply { closeConnection(true) }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val origin = session.headers["origin"]
        val host = session.headers["host"]
        if (origin != null && (host == null || !origin.equals("http://$host", ignoreCase = true))) {
            return json(Response.Status.FORBIDDEN, """{"error":"forbidden"}""").apply { closeConnection(true) }
        }
        val length = session.headers["content-length"]?.toLongOrNull()
        val early = when {
            length == null || length <= 0L -> SubtitleFontImportResult.INVALID
            length > SubtitleFontStore.MAX_FONT_BYTES -> SubtitleFontImportResult.TOO_LARGE
            else -> null
        }
        val result = early
            ?: SubtitleFontStore.importFromStream(context, session.inputStream, declaredLength = length)
        onImported(result)
        return if (result == SubtitleFontImportResult.IMPORTED) {
            json(
                Response.Status.OK,
                """{"status":"imported","font":${jsonString(SubtitleFontStore.current(context)?.familyName)}}""",
            )
        } else {
            json(Response.Status.BAD_REQUEST, """{"error":"${result.name.lowercase()}"}""").apply {
                if (early != null) closeConnection(true)
            }
        }
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        val escaped = buildString {
            value.forEach { char ->
                when {
                    char == '"' -> append("\\\"")
                    char == '\\' -> append("\\\\")
                    char < ' ' -> append(' ')
                    else -> append(char)
                }
            }
        }
        return "\"$escaped\""
    }

    companion object {
        private const val START_PORT = 8100
        private const val MAX_ATTEMPTS = 10

        private fun newToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        fun startOnAvailablePort(
            context: Context,
            onImported: (SubtitleFontImportResult) -> Unit,
        ): SubtitleFontUploadServer? {
            for (port in START_PORT until START_PORT + MAX_ATTEMPTS) {
                try {
                    val server = SubtitleFontUploadServer(context.applicationContext, onImported, port)
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}

private object SubtitleFontUploadWebPage {
    fun html(context: Context, fontPath: String): String {
        fun text(id: Int): String = context.getString(id)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "&#39;")
            .replace("\"", "&quot;")

        fun js(value: String): String = buildString {
            value.forEach { char ->
                when {
                    char == '\\' -> append("\\\\")
                    char == '\'' -> append("\\'")
                    char == '"' -> append("\\\"")
                    char == '<' -> append("\\u003c")
                    char == '>' -> append("\\u003e")
                    char == '&' -> append("\\u0026")
                    char == '\n' -> append("\\n")
                    char == '\r' -> append("\\r")
                    char == '\u2028' -> append("\\u2028")
                    char == '\u2029' -> append("\\u2029")
                    else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                }
            }
        }
        fun jsText(id: Int): String = js(context.getString(id))

        val title = text(R.string.subtitle_font_web_title)
        val subtitle = text(R.string.subtitle_font_web_subtitle)
        val choose = text(R.string.subtitle_font_web_choose)
        val upload = text(R.string.subtitle_font_web_upload)
        val current = text(R.string.subtitle_font_web_current)
        val chooseJs = jsText(R.string.subtitle_font_web_choose)
        val uploading = jsText(R.string.subtitle_font_web_uploading)
        val defaultFont = jsText(R.string.subtitle_font_default)
        val imported = jsText(R.string.subtitle_font_web_imported)
        val invalid = jsText(R.string.subtitle_font_invalid)
        val tooLarge = jsText(R.string.subtitle_font_too_large)
        val connection = jsText(R.string.subtitle_font_web_connection_error)
        val api = js(fontPath)
        val maxBytes = SubtitleFontStore.MAX_FONT_BYTES

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#000;color:#fff;
display:flex;justify-content:center;align-items:center;min-height:100vh;padding:16px}
.card{max-width:560px;width:100%;padding:32px 0}
h1{font-size:20px;font-weight:600;margin-bottom:4px}
.subtitle{font-size:13px;color:rgba(255,255,255,0.5);margin-bottom:24px}
input[type=file]{width:100%;padding:12px 1rem;border-radius:16px;border:1px solid rgba(255,255,255,0.14);
color:#fff;font-size:0.875rem}
button{width:100%;margin-top:16px;padding:0.875rem 1.3rem;border:1px solid rgba(255,255,255,0.2);border-radius:100px;
background:transparent;color:#fff;font-size:0.875rem;font-weight:500;cursor:pointer}
button:disabled{opacity:.4}
.status{margin-top:12px;font-size:13px;text-align:center;min-height:20px}
.status.ok{color:#4caf50}.status.err{color:#ef5350}
.current{margin-top:20px;padding:12px;background:rgba(255,255,255,0.05);border-radius:12px;
border:1px solid rgba(255,255,255,0.08);font-size:13px;color:rgba(255,255,255,0.6)}
</style>
</head>
<body>
<div class="card">
  <h1>$title</h1>
  <p class="subtitle">$subtitle</p>
  <label for="file" style="display:none">$choose</label>
  <input id="file" type="file" accept=".ttf,.otf,font/ttf,font/otf,application/x-font-ttf,application/x-font-otf,application/octet-stream">
  <button id="send" onclick="send()">$upload</button>
  <div id="status" class="status"></div>
  <div class="current">$current <span id="current">…</span></div>
</div>
<script>
const st=document.getElementById('status'),cur=document.getElementById('current'),btn=document.getElementById('send');
function msg(t,ok){st.textContent=t;st.className='status '+(ok?'ok':'err')}
async function load(){try{const r=await fetch('$api');const d=await r.json();cur.textContent=d.font||'$defaultFont'}catch(e){cur.textContent='?'}}
async function send(){const f=document.getElementById('file').files[0];if(!f){msg('$chooseJs',false);return}
if(f.size>$maxBytes){msg('$tooLarge',false);return}
btn.disabled=true;msg('$uploading',true);
try{const r=await fetch('$api',{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:f});
let d={};try{d=await r.json()}catch(e){}
if(r.ok){msg('$imported',true);cur.textContent=d.font||'$defaultFont'}
else{msg(d.error==='too_large'?'$tooLarge':'$invalid',false)}}
catch(e){msg('$connection',false)}finally{btn.disabled=false}}
load();
</script>
</body>
</html>
""".trimIndent()
    }
}
