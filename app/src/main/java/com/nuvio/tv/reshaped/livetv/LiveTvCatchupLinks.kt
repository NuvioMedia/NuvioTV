package com.nuvio.tv.reshaped.livetv

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Links to past programmes (catch-up) in the forms IPTV providers use. Everything here is plain
 * string work done once per play, never per frame or per channel.
 */
internal object LiveTvCatchupLinks {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Whether [programme] of a channel with [catchup] can be played again now. */
    fun isPlayable(catchup: LiveTvCatchup?, programme: LiveTvProgramme, nowMs: Long): Boolean =
        isPlayableFrom(catchup, programme.startEpochMs, nowMs)

    /** Whether a channel with [catchup] can be played again from [startMs]. */
    fun isPlayableFrom(catchup: LiveTvCatchup?, startMs: Long, nowMs: Long): Boolean =
        catchup != null && startMs < nowMs && nowMs - startMs <= catchup.days * DAY_MS

    /** An Xtream live link `http://host/live/user/pass/id.ext` (or without `/live`), split up. */
    private val XTREAM_LIVE = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.[A-Za-z0-9]+)?$""", RegexOption.IGNORE_CASE)

    /** The stream id an Xtream live link ends in. */
    fun xtreamStreamId(liveUrl: String): String? =
        XTREAM_LIVE.find(liveUrl.substringBefore('?'))?.groupValues?.get(4)

    /** Whether [liveUrl] is an Xtream panel's own live link (".../live/user/pass/id.ts"), whatever list it came in. */
    fun isXtreamLiveLink(liveUrl: String): Boolean {
        val match = XTREAM_LIVE.find(liveUrl.substringBefore('?')) ?: return false
        return liveUrl.regionMatches(match.groupValues[1].length, "/live/", 0, 6, ignoreCase = true)
    }

    /** The panel login an Xtream live link carries: server, user, password. */
    fun xtreamLogin(liveUrl: String): Triple<String, String, String>? =
        XTREAM_LIVE.find(liveUrl.substringBefore('?'))?.destructured?.let { (host, user, pass) ->
            Triple(host, decode(user), decode(pass))
        }

    /**
     * The link to the programme from [startMs] to [stopMs] on a channel playing [liveUrl].
     * [panelZone] is the Xtream panel's time zone (see [LiveTvXtream.zone]); null for other kinds.
     */
    fun link(
        liveUrl: String,
        catchup: LiveTvCatchup,
        startMs: Long,
        stopMs: Long,
        nowMs: Long,
        panelZone: ZoneId?,
        /** Whether the channel comes from an Xtream panel (its own source, or its get.php list). */
        xtreamPanel: Boolean = true,
        /** An Xtream panel's replay as HLS (`.m3u8`), which has a length and seeks, whatever the live link's format. */
        hls: Boolean = false,
    ): String? {
        val start = startMs / 1000
        val end = maxOf(stopMs / 1000, start + 60)
        val now = nowMs / 1000
        val template = catchup.template
        return when (catchup.kind) {
            LiveTvCatchup.Kind.Xtream -> xtream(liveUrl, startMs, end - start, panelZone, hls)
            LiveTvCatchup.Kind.Append -> template?.let { liveUrl + fill(it, start, end, now) }
            LiveTvCatchup.Kind.Default -> when {
                template == null -> shift(liveUrl, start, now)
                template.isHttpUrl() -> fill(template, start, end, now)
                else -> liveUrl + fill(template, start, end, now)
            }
            // Xtream panels list "shift" catch-up in their M3U too, but only answer their own form.
            LiveTvCatchup.Kind.Shift ->
                (if (xtreamPanel) xtream(liveUrl, startMs, end - start, panelZone, hls) else null) ?: shift(liveUrl, start, now)
            LiveTvCatchup.Kind.Flussonic -> flussonic(liveUrl, start, end - start)
        }
    }

    /** Whether [catchup] on [liveUrl] is an Xtream panel's /timeshift/ replay, which can be asked for as HLS. */
    fun isXtreamReplay(liveUrl: String, catchup: LiveTvCatchup, xtreamPanel: Boolean): Boolean =
        needsPanelZone(liveUrl, catchup, xtreamPanel) && !liveUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    /** Whether the link for [catchup] on [liveUrl] needs the Xtream panel's time zone. */
    fun needsPanelZone(liveUrl: String, catchup: LiveTvCatchup, xtreamPanel: Boolean = true): Boolean =
        (catchup.kind == LiveTvCatchup.Kind.Xtream || (catchup.kind == LiveTvCatchup.Kind.Shift && xtreamPanel)) &&
            xtreamLogin(liveUrl) != null

    private val XTREAM_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")

    private fun xtream(liveUrl: String, startMs: Long, durationSeconds: Long, zone: ZoneId?, hls: Boolean = false): String? {
        val match = XTREAM_LIVE.find(liveUrl.substringBefore('?')) ?: return null
        val (host, user, pass, id) = match.destructured
        val extension = if (hls) ".m3u8" else match.groupValues[5].ifEmpty { ".ts" }
        val minutes = (durationSeconds + 59) / 60
        val time = XTREAM_TIME.format(Instant.ofEpochMilli(startMs).atZone(zone ?: ZoneId.systemDefault()))
        return "$host/timeshift/$user/$pass/$minutes/$time/$id$extension"
    }

    private fun shift(liveUrl: String, start: Long, now: Long): String {
        val separator = if ('?' in liveUrl) '&' else '?'
        return "$liveUrl${separator}utc=$start&lutc=$now"
    }

    private val FLUSSONIC = Regex("""^(https?://[^/]+)/([^?]*?)(?:/([^/?]*))?(\?.*)?$""", RegexOption.IGNORE_CASE)

    private fun flussonic(liveUrl: String, start: Long, duration: Long): String? {
        val match = FLUSSONIC.find(liveUrl) ?: return null
        val (host, stream, file, query) = match.destructured
        if (stream.isEmpty()) return null
        return if (file.isEmpty() || file.endsWith(".m3u8")) {
            val base = file.removeSuffix(".m3u8").ifEmpty { "index" }
            "$host/$stream/$base-$start-$duration.m3u8$query"
        } else {
            "$host/$stream/timeshift_abs-$start.ts$query"
        }
    }

    private val FORMATTED = Regex("""\$?\{(utc|start|utcend|end|lutc|now|timestamp):([^}]+)\}""")
    private val DIVIDED = Regex("""\$?\{(duration|offset):(\d+)\}""")
    private val PLAIN = Regex("""\$?\{(utc|start|utcend|end|lutc|now|timestamp|duration|offset|Y|m|d|H|M|S)\}""")

    /**
     * Fills a `catchup-source` template: `{utc}`, `{lutc}`, `{utcend}`, `{duration}`, `{offset}` (also
     * written `${…}`), `{utc:Y-m-d H:M:S}` style dates (UTC) and `{Y}{m}{d}{H}{M}{S}` (the start, local time).
     */
    internal fun fill(template: String, start: Long, end: Long, now: Long): String {
        if ('{' !in template) return template
        val duration = end - start
        fun epochFor(name: String) = when (name) {
            "utcend", "end" -> end
            "lutc", "now", "timestamp" -> now
            else -> start
        }
        var out = FORMATTED.replace(template) { match ->
            formatPattern(match.groupValues[2], epochFor(match.groupValues[1]))
        }
        out = DIVIDED.replace(out) { match ->
            val divisor = match.groupValues[2].toLongOrNull()?.coerceAtLeast(1) ?: 1
            val value = if (match.groupValues[1] == "duration") duration else now - start
            (value / divisor).toString()
        }
        val local = Instant.ofEpochSecond(start).atZone(ZoneId.systemDefault())
        out = PLAIN.replace(out) { match ->
            when (val name = match.groupValues[1]) {
                "duration" -> duration.toString()
                "offset" -> (now - start).toString()
                "Y" -> "%04d".format(Locale.ROOT, local.year)
                "m" -> "%02d".format(Locale.ROOT, local.monthValue)
                "d" -> "%02d".format(Locale.ROOT, local.dayOfMonth)
                "H" -> "%02d".format(Locale.ROOT, local.hour)
                "M" -> "%02d".format(Locale.ROOT, local.minute)
                "S" -> "%02d".format(Locale.ROOT, local.second)
                else -> epochFor(name).toString()
            }
        }
        return out
    }

    /** `Y-m-d H:M:S` style (the letters IPTV templates use) for an epoch second, in UTC. */
    private fun formatPattern(pattern: String, epochSecond: Long): String {
        val time = Instant.ofEpochSecond(epochSecond).atOffset(ZoneOffset.UTC)
        return buildString {
            pattern.forEach { char ->
                when (char) {
                    'Y' -> append("%04d".format(Locale.ROOT, time.year))
                    'm' -> append("%02d".format(Locale.ROOT, time.monthValue))
                    'd' -> append("%02d".format(Locale.ROOT, time.dayOfMonth))
                    'H' -> append("%02d".format(Locale.ROOT, time.hour))
                    'M' -> append("%02d".format(Locale.ROOT, time.minute))
                    'S' -> append("%02d".format(Locale.ROOT, time.second))
                    else -> append(char)
                }
            }
        }
    }

    /** A path segment decoded: "%xx" only (a "+" in a path is a plus, not a space). */
    private fun decode(text: String): String =
        runCatching { java.net.URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrDefault(text)
}
