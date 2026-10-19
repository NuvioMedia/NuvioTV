package com.nuvio.tv.core.livetv

import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvImportResult
import com.nuvio.tv.domain.model.LiveTvProgram
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.FilterReader
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/** Streaming XMLTV parser with bounded input/output and external-entity processing disabled. */
object XmlTvParser {
    const val MAX_DOCUMENT_CHARS = 16 * 1024 * 1024
    const val MAX_CHANNELS = 5_000
    const val MAX_PROGRAMS = 20_000
    private const val MAX_FIELD_CHARS = 2_048
    private val localTimestampFormatter = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
        .withResolverStyle(ResolverStyle.STRICT)
    private val offsetTimestampFormatter = DateTimeFormatter.ofPattern("uuuuMMddHHmmssZ")
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(text: String, sourceId: String): LiveTvImportResult =
        parse(StringReader(text), sourceId)

    fun parse(reader: Reader, sourceId: String): LiveTvImportResult {
        require(sourceId.isNotBlank() && sourceId.length <= 120 && sourceId.none(Char::isISOControl)) {
            "Source id is invalid"
        }
        val handler = XmlTvHandler(sourceId)
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            val xmlReader = factory.newSAXParser().xmlReader.apply {
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                runCatching { setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
                runCatching { setProperty("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
                contentHandler = handler
                entityResolver = org.xml.sax.EntityResolver { _, _ ->
                    throw SAXException("External XML entities are not supported")
                }
            }
            xmlReader.parse(InputSource(BoundedReader(reader, MAX_DOCUMENT_CHARS)))
        } catch (error: SAXException) {
            throw LiveTvPlaylistFormatException("XMLTV document is invalid or uses unsupported XML features")
        } catch (error: IOException) {
            throw LiveTvPlaylistFormatException("XMLTV document exceeds the supported size or could not be read")
        } catch (error: Exception) {
            throw LiveTvPlaylistFormatException("XMLTV parser configuration failed securely")
        }
        return handler.result()
    }

    private fun parseTimestamp(value: String?): Long? {
        val trimmed = value?.trim().orEmpty()
        val match = Regex("^(\\d{14})(?:\\s+([+-]\\d{4}|Z))?$").matchEntire(trimmed) ?: return null
        val date = match.groupValues[1]
        val zone = match.groupValues[2]
        return try {
            if (zone.isBlank() || zone == "Z") {
                LocalDateTime.parse(date, localTimestampFormatter).toInstant(ZoneOffset.UTC).toEpochMilli()
            } else {
                Instant.from(offsetTimestampFormatter.parse(date + zone)).toEpochMilli()
            }
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private class XmlTvHandler(private val sourceId: String) : DefaultHandler() {
        private val channels = ArrayList<LiveTvChannel>()
        private val programs = ArrayList<LiveTvProgram>()
        private var skipped = 0
        private var channelId: String? = null
        private var channelName: String? = null
        private var channelIcon: String? = null
        private var programme: PendingProgramme? = null
        private var textTag: String? = null
        private var textBuffer = StringBuilder()
        private var textLanguage: String? = null

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (qName) {
                "channel" -> {
                    channelId = attributes.getValue("id")?.clean(160)?.ifBlank { null }
                    channelName = null
                    channelIcon = null
                }
                "programme" -> {
                    programme = PendingProgramme(
                        channelId = attributes.getValue("channel")?.clean(160).orEmpty(),
                        startMillis = parseTimestamp(attributes.getValue("start")),
                        endMillis = parseTimestamp(attributes.getValue("stop")),
                    )
                }
                "display-name", "title", "desc", "category" -> {
                    textTag = qName
                    textBuffer = StringBuilder()
                    textLanguage = attributes.getValue("lang")?.lowercase()
                }
                "icon" -> {
                    val url = attributes.getValue("src")?.takeIf { it.length <= 2_048 && isHttpUrl(it) }
                    if (url != null) {
                        if (channelId != null) channelIcon = url
                        programme?.iconUrl = url
                    }
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (textTag != null && textBuffer.length < MAX_FIELD_CHARS) {
                textBuffer.append(ch, start, minOf(length, MAX_FIELD_CHARS - textBuffer.length))
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (qName == textTag) {
                val value = textBuffer.toString().clean(MAX_FIELD_CHARS).ifBlank { null }
                when (qName) {
                    "display-name" -> if (value != null && (channelName == null || textLanguage?.startsWith("pt") == true)) {
                        channelName = value
                    }
                    "title" -> if (value != null && (programme?.title == null || textLanguage?.startsWith("pt") == true)) {
                        programme?.title = value
                    }
                    "desc" -> if (value != null) programme?.description = value
                    "category" -> if (value != null && programme?.category == null) programme?.category = value
                }
                textTag = null
                textLanguage = null
                textBuffer = StringBuilder()
            }
            when (qName) {
                "channel" -> {
                    val id = channelId
                    val name = channelName
                    if (id != null && name != null && channels.size < MAX_CHANNELS) {
                        channels += LiveTvChannel(
                            id = "$sourceId:xmltv:$id",
                            sourceId = sourceId,
                            displayName = name,
                            streamUrl = null,
                            logoUrl = channelIcon,
                            tvgId = id,
                        )
                    } else {
                        skipped++
                    }
                    channelId = null
                    channelName = null
                    channelIcon = null
                }
                "programme" -> {
                    val item = programme
                    if (item == null || item.channelId.isBlank() || item.title.isNullOrBlank() ||
                        item.startMillis == null || item.endMillis == null || item.endMillis <= item.startMillis
                    ) {
                        skipped++
                    } else if (programs.size >= MAX_PROGRAMS) {
                        throw SAXException("XMLTV exceeds the programme limit")
                    } else {
                        programs += LiveTvProgram(
                            sourceId = sourceId,
                            channelId = item.channelId,
                            title = item.title!!,
                            startEpochMillis = item.startMillis,
                            endEpochMillis = item.endMillis,
                            description = item.description,
                            category = item.category,
                            iconUrl = item.iconUrl,
                        )
                    }
                    programme = null
                }
            }
        }

        fun result() = LiveTvImportResult(channels = channels, programs = programs, skippedEntries = skipped)

        private data class PendingProgramme(
            val channelId: String,
            val startMillis: Long?,
            val endMillis: Long?,
            var title: String? = null,
            var description: String? = null,
            var category: String? = null,
            var iconUrl: String? = null,
        )
    }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun String.clean(maxChars: Int): String = filterNot(Char::isISOControl).trim().take(maxChars)

    private class BoundedReader(reader: Reader, private val maxChars: Int) : FilterReader(reader) {
        private var count = 0
        override fun read(): Int {
            if (count >= maxChars) {
                val next = super.read()
                if (next < 0) return -1
                throw IOException("XMLTV character limit exceeded")
            }
            val next = super.read()
            if (next >= 0) count++
            return next
        }
        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (count >= maxChars) {
                val next = super.read()
                if (next < 0) return -1
                throw IOException("XMLTV character limit exceeded")
            }
            val read = super.read(buffer, offset, minOf(length, maxChars - count))
            if (read > 0) count += read
            return read
        }
    }
}
