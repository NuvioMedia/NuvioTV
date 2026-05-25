package com.nuvio.tv.iptv.data.repository

import com.nuvio.tv.iptv.data.model.IptvChannel
import com.nuvio.tv.iptv.data.model.IptvSampleData

class IptvRepository {
        fun getChannels(): List<IptvChannel> {
                    return IptvSampleData.channels
        }
}
        }
}