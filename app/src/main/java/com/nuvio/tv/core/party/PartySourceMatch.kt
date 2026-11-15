package com.nuvio.tv.core.party

import com.nuvio.tv.domain.model.Stream

internal fun PartySource.matches(stream: Stream): Boolean {
    val hash = infoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val streamHash = stream.getEffectiveInfoHash()?.lowercase()
    if (hash != null && streamHash != null) {
        val index = fileIdx
        val streamIndex = stream.getEffectiveFileIdx()
        return hash == streamHash && (index == null || streamIndex == null || index == streamIndex)
    }
    val file = filename?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val streamFile = (stream.behaviorHints?.filename ?: stream.clientResolve?.filename)?.trim()
    return !streamFile.isNullOrEmpty() && file.equals(streamFile, ignoreCase = true)
}
