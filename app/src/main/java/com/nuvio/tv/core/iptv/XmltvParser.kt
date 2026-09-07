package com.nuvio.tv.core.iptv

import android.util.Xml
import com.nuvio.tv.domain.model.EpgProgramme
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * Parses an XMLTV EPG feed (https://wiki.xmltv.org/index.php/XMLTVFormat) into
 * a flat list of programmes. Transparently handles gzip-compressed feeds
 * (common for EPG providers, e.g. epgshare01's *.xml.gz files) by sniffing
 * the gzip magic bytes rather than trusting the URL's file extension.
 */
object XmltvParser {
    // yyyyMMddHHmmss optionally followed by a space and a timezone offset, e.g.
    // "20260907120000 +0000" or "20260907120000".
    private val formatWithZone = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val formatNoZone = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parseXmltvTime(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.length < 14) return null
        val pos = ParsePosition(0)
        formatWithZone.parse(trimmed, pos)?.let { if (pos.index > 0) return it.time }
        return runCatching { formatNoZone.parse(trimmed.take(14))?.time }.getOrNull()
    }

    fun parse(rawStream: InputStream): List<EpgProgramme> {
        val stream = autoDecompress(rawStream)
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        val programmes = mutableListOf<EpgProgramme>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "programme") {
                val channelId = parser.getAttributeValue(null, "channel")
                val start = parser.getAttributeValue(null, "start")
                val stop = parser.getAttributeValue(null, "stop")
                var title: String? = null

                var depth = 1
                while (depth > 0) {
                    eventType = parser.next()
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            depth++
                            if (parser.name == "title" && title == null) {
                                title = if (parser.next() == XmlPullParser.TEXT) parser.text else null
                            }
                        }
                        XmlPullParser.END_TAG -> depth--
                        XmlPullParser.END_DOCUMENT -> depth = 0
                    }
                }

                val startMillis = start?.let { parseXmltvTime(it) }
                val stopMillis = stop?.let { parseXmltvTime(it) }
                if (channelId != null && title != null && startMillis != null && stopMillis != null) {
                    programmes.add(EpgProgramme(channelId, title, startMillis, stopMillis))
                }
            }
            eventType = parser.next()
        }
        return programmes
    }

    /** Sniffs the gzip magic number (0x1f 0x8b) rather than trusting the URL's extension. */
    private fun autoDecompress(input: InputStream): InputStream {
        val buffered = input.buffered()
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered) else buffered
    }
}
