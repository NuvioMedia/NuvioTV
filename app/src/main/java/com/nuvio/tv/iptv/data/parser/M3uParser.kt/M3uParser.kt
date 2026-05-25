package com.nuvio.tv.iptv.data.parser

import com.nuvio.tv.iptv.data.model.IptvChannel

class M3uParser {
        fun parse(content: String): List<IptvChannel> {
                    val channels = mutableListOf<IptvChannel>()
                            val lines = content.lines()
                                    var currentMeta: String? = null

                                            for (line in lines) {
                                                            if (line.startsWith("#EXTINF")) {
                                                                                currentMeta = line
                                                            } else if (line.isNotBlank() && !line.startsWith("#")) {
                                                                                val meta = currentMeta.orEmpty()
                                                                                                val name = meta.substringAfterLast(",").trim()
                                                                                                                val tvgId = Regex("""tvg-id="([^"]*)"""").find(meta)?.groupValues?.get(1)
                                                                                                                                val logo = Regex("""tvg-logo="([^"]*)"""").find(meta)?.groupValues?.get(1)
                                                                                                                                                val group = Regex("""group-title="([^"]*)"""").find(meta)?.groupValues?.get(1)

                                                                                                                                                                channels += IptvChannel(
                                                                                                                                                                                        id = line.hashCode().toString(),
                                                                                                                                                                                                            tvgId = tvgId,
                                                                                                                                                                                                                                name = name,
                                                                                                                                                                                                                                                    logoUrl = logo,
                                                                                                                                                                                                                                                                        groupTitle = group,
                                                                                                                                                                                                                                                                                            streamUrl = line.trim()
                                                                                                                                                                )
                                                                                                                                                                                currentMeta = null
                                                            }
                                            }
                                                    return channels
        }
}
                                                                                                                                                                )
                                                            }
                                                            }
                                            }
        }
}