package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.Stream

internal fun groupStreamsByProvider(streams: List<Stream>): List<Stream> =
    streams.groupBy { it.addonName }.values.flatMap { addonStreams ->
        addonStreams.groupBy { it.pluginSourceName?.takeIf(String::isNotBlank) ?: it.addonName }
            .entries.sortedBy { it.key.lowercase() }
            .flatMap { it.value }
    }

internal fun streamProviderHeader(stream: Stream, previous: Stream? = null): String? =
    stream.pluginSourceName?.takeIf {
        it.isNotBlank() && it != stream.addonName &&
            (previous?.addonName != stream.addonName || previous.pluginSourceName != it)
    }
