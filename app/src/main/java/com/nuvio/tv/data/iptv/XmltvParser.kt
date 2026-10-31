package com.nuvio.tv.data.iptv

import org.xmlpull.v1.XmlPullParser

object XmltvParser {

    private val prefixRegex = Regex("""^[a-z]{2,3}\s*[:|]\s*""")
    private val bracketRegex = Regex("""[\[(].*?[\])]""")
    private val qualitySuffixRegex = Regex("""(fhd|uhd|hd|sd|4k|hevc|h265|backup|raw)+$""")
    private val nonAlnumRegex = Regex("""[^\p{L}\p{N}]""")

    fun parse(
        parser: XmlPullParser,
        channels: List<IptvChannel>,
        windowStart: Long,
        windowEnd: Long,
        into: MutableMap<String, MutableList<IptvProgramme>>
    ) {
        val tvgIds = HashSet<String>()
        val wantedNames = HashSet<String>()
        channels.forEach { channel ->
            channel.tvgId?.lowercase()?.let { tvgIds.add(it) }
            channel.tvgName?.let { normalizeName(it) }?.takeIf { it.isNotEmpty() }?.let { wantedNames.add(it) }
            normalizeName(channel.name).takeIf { it.isNotEmpty() }?.let { wantedNames.add(it) }
        }

        val nameToXmlId = HashMap<String, String>()
        val wantedXmlIds = HashSet<String>()
        val programmesByXmlId = HashMap<String, ArrayList<IptvProgramme>>()
        val interned = HashMap<String, String>()
        fun intern(value: String): String = interned.getOrPut(value) { value }

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> {
                            val id = parser.getAttributeValue(null, "id")?.trim()?.lowercase()
                            val names = readChannelNames(parser)
                            if (!id.isNullOrEmpty()) {
                                var wanted = id in tvgIds
                                names.forEach { name ->
                                    val normalized = normalizeName(name)
                                    if (normalized.isNotEmpty() && normalized in wantedNames) {
                                        wanted = true
                                        nameToXmlId.putIfAbsent(normalized, id)
                                    }
                                }
                                if (wanted) wantedXmlIds.add(id)
                            }
                        }
                        "programme" -> {
                            val id = parser.getAttributeValue(null, "channel")?.trim()?.lowercase()
                            val start = parser.getAttributeValue(null, "start")?.let { parseXmltvTime(it) }
                            val stop = parser.getAttributeValue(null, "stop")?.let { parseXmltvTime(it) }
                            val wanted = id != null && (id in wantedXmlIds || id in tvgIds) &&
                                start != null && stop != null && stop > start &&
                                stop > windowStart && start < windowEnd
                            if (!wanted) {
                                skip(parser)
                            } else {
                                val programme = readProgramme(parser, start!!, stop!!, ::intern)
                                if (programme != null) {
                                    programmesByXmlId.getOrPut(id!!) { ArrayList() }.add(programme)
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (error: Exception) {
            if (programmesByXmlId.isEmpty()) throw error
        }

        channels.forEach { channel ->
            val byId = channel.tvgId?.lowercase()?.let { programmesByXmlId[it] }
            val list = byId ?: run {
                val candidates = listOfNotNull(channel.tvgName, channel.name)
                    .map { normalizeName(it) }
                    .filter { it.isNotEmpty() }
                candidates.firstNotNullOfOrNull { name ->
                    nameToXmlId[name]?.let { programmesByXmlId[it] }
                }
            }
            if (!list.isNullOrEmpty()) {
                into.getOrPut(channel.key) { ArrayList() }.addAll(list)
            }
        }
    }

    fun normalizeProgrammes(list: List<IptvProgramme>): List<IptvProgramme> {
        if (list.isEmpty()) return list
        val sorted = list.sortedWith(compareBy<IptvProgramme> { it.start }.thenBy { it.end })
        val result = ArrayList<IptvProgramme>(sorted.size)
        for (programme in sorted) {
            val last = result.lastOrNull()
            if (last == null) {
                result.add(programme)
                continue
            }
            if (programme.start >= last.end) {
                result.add(programme)
            } else if (programme.end > last.end) {
                result.add(programme.copy(start = last.end))
            }
        }
        return result
    }

    fun normalizeName(value: String): String {
        var result = value.lowercase().trim()
        result = prefixRegex.replace(result, "")
        result = bracketRegex.replace(result, "")
        result = nonAlnumRegex.replace(result, "")
        val stripped = qualitySuffixRegex.replace(result, "")
        return stripped.ifEmpty { result }
    }

    fun parseXmltvTime(value: String): Long? {
        val s = value.trim()
        if (s.length < 12) return null
        fun num(from: Int, len: Int): Int? {
            if (from + len > s.length) return null
            var out = 0
            for (i in from until from + len) {
                val c = s[i]
                if (c !in '0'..'9') return null
                out = out * 10 + (c - '0')
            }
            return out
        }
        val year = num(0, 4) ?: return null
        val month = num(4, 2) ?: return null
        val day = num(6, 2) ?: return null
        val hour = num(8, 2) ?: return null
        val minute = num(10, 2) ?: return null
        val second = num(12, 2) ?: 0
        if (month !in 1..12 || day !in 1..31) return null

        var offsetSeconds = 0
        var signIndex = -1
        for (i in 12 until s.length) {
            val c = s[i]
            if (c == '+' || c == '-') {
                signIndex = i
                break
            }
        }
        if (signIndex >= 0) {
            val digits = s.substring(signIndex + 1).filter { it.isDigit() }
            if (digits.length >= 2) {
                val oh = digits.substring(0, 2).toInt()
                val om = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
                offsetSeconds = (oh * 3600 + om * 60) * if (s[signIndex] == '-') -1 else 1
            }
        }
        val days = daysFromCivil(year, month, day)
        val epochSeconds = days * 86_400L + hour * 3_600L + minute * 60L + second - offsetSeconds
        return epochSeconds * 1000L
    }

    private fun daysFromCivil(yearIn: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) yearIn - 1 else yearIn).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + if (month > 2) -3 else 9).toLong()
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    private fun readChannelNames(parser: XmlPullParser): List<String> {
        val names = ArrayList<String>(2)
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "display-name") {
                readText(parser).trim().takeIf { it.isNotEmpty() }?.let { names.add(it) }
            }
            event = parser.next()
        }
        return names
    }

    private fun readProgramme(
        parser: XmlPullParser,
        start: Long,
        stop: Long,
        intern: (String) -> String
    ): IptvProgramme? {
        var title: String? = null
        var subtitle: String? = null
        var description: String? = null
        var category: String? = null
        var icon: String? = null
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> {
                        val text = readText(parser).trim()
                        if (title == null && text.isNotEmpty()) title = intern(text)
                    }
                    "sub-title" -> {
                        val text = readText(parser).trim()
                        if (subtitle == null && text.isNotEmpty()) subtitle = text
                    }
                    "desc" -> {
                        val text = readText(parser).trim()
                        if (description == null && text.isNotEmpty()) description = text
                    }
                    "category" -> {
                        val text = readText(parser).trim()
                        if (category == null && text.isNotEmpty()) category = intern(text)
                    }
                    "icon" -> {
                        if (icon == null) icon = parser.getAttributeValue(null, "src")?.takeIf { it.isNotBlank() }
                        skip(parser)
                    }
                    else -> skip(parser)
                }
            }
            event = parser.next()
        }
        val resolvedTitle = title ?: return null
        return IptvProgramme(
            start = start,
            end = stop,
            title = resolvedTitle,
            subtitle = subtitle,
            description = description,
            category = category,
            icon = icon
        )
    }

    private fun readText(parser: XmlPullParser): String {
        val depth = parser.depth
        val builder = StringBuilder()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT || event == XmlPullParser.ENTITY_REF) {
                parser.text?.let { builder.append(it) }
            }
            event = parser.next()
        }
        return builder.toString()
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            event = parser.next()
        }
    }
}
