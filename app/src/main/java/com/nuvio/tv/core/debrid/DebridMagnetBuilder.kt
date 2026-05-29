package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.Stream
import java.net.URLEncoder

object DebridMagnetBuilder {
    fun fromStream(stream: Stream): String? {
        stream.torrentMagnetUri()?.takeIf { it.isNotBlank() }?.let { return it }
        val hash = stream.infoHash?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return buildString {
            append("magnet:?xt=urn:btih:")
            append(hash)
            stream.behaviorHints?.filename
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { filename ->
                    append("&dn=")
                    append(encode(filename))
                }
            stream.sources.orEmpty()
                .mapNotNull { source -> source.trackerUrlOrNull() }
                .distinct()
                .forEach { tracker ->
                    append("&tr=")
                    append(encode(tracker))
                }
        }
    }

    private fun String.trackerUrlOrNull(): String? {
        val value = trim()
        if (value.isBlank() || value.startsWith("dht:", ignoreCase = true)) return null
        return value.removePrefix("tracker:").trim().takeIf { it.isNotBlank() }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
}
