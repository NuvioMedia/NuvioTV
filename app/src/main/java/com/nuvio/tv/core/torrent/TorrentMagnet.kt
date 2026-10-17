package com.nuvio.tv.core.torrent

internal val DefaultTorrentTrackers = listOf(
    "udp://zer0day.ch:1337/announce",
    "udp://tracker.publictracker.xyz:6969/announce",
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://open.stealth.si:80/announce",
    "http://tracker.renfei.net:8080/announce",
    "udp://udp.tracker.projectk.org:23333/announce",
    "udp://tracker.tryhackx.org:6969/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://tracker.theoks.net:6969/announce",
    "udp://tracker.startwork.cv:1337/announce",
    "udp://tracker.qu.ax:6969/announce",
    "udp://tracker.plx.im:6969/announce",
    "udp://tracker.nyaa.vc:6969/announce",
    "udp://tracker.iperson.xyz:6969/announce",
    "udp://tracker.gmi.gd:6969/announce",
    "udp://tracker.fnix.net:6969/announce",
    "udp://tracker.flatuslifir.is:6969/announce",
    "udp://tracker.ducks.party:1984/announce",
    "udp://tracker.bluefrog.pw:2710/announce"
)

internal fun canonicalInfoHash(infoHash: String): String {
    val canonical = infoHash.trim().lowercase()
    require(
        (canonical.length == 40 || canonical.length == 64) &&
            canonical.all { it in '0'..'9' || it in 'a'..'f' }
    ) {
        "Torrent info hash must be 40 or 64 hexadecimal characters"
    }
    return canonical
}

internal fun buildMagnetUri(infoHash: String, trackers: List<String>): String {
    val canonicalHash = canonicalInfoHash(infoHash)
    val topic = if (canonicalHash.length == 40) {
        "urn:btih:$canonicalHash"
    } else {
        "urn:btmh:1220$canonicalHash"
    }
    val trackerParameters = trackers.filter(String::isNotBlank).distinct().joinToString("") { tracker ->
        "&tr=${tracker.encodeQueryValue()}"
    }
    return "magnet:?xt=$topic$trackerParameters"
}

private fun String.encodeQueryValue(): String = buildString {
    for (byte in this@encodeQueryValue.encodeToByteArray()) {
        val value = byte.toInt() and 0xff
        if ((value in 'a'.code..'z'.code) ||
            (value in 'A'.code..'Z'.code) ||
            (value in '0'.code..'9'.code) ||
            value == '-'.code || value == '.'.code || value == '_'.code || value == '~'.code
        ) {
            append(value.toChar())
        } else {
            append('%')
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
}

private const val HEX_DIGITS = "0123456789ABCDEF"
