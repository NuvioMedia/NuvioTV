package com.nuvio.tv.iptv.data.model

data class IptvSource(
        val id: String,
            val name: String,
                val playlistUrl: String,
                    val epgUrl: String? = null,
                        val userAgent: String? = null
)

data class IptvChannel(
        val id: String,
            val tvgId: String?,
                val name: String,
                    val logoUrl: String?,
                        val groupTitle: String?,
                            val streamUrl: String
)

data class EpgProgram(
        val id: String,
            val channelTvgId: String?,
                val title: String,
                    val description: String?,
                        val startMillis: Long,
                            val stopMillis: Long
)
)
)
)