package com.nuvio.tv.iptv.ui.channels

import androidx.compose.runtime.Composable
import com.nuvio.tv.iptv.data.repository.IptvRepository

@Composable
fun IptvChannelsRoute() {
        val repository = IptvRepository()
            val channels = repository.getChannels()

                IptvChannelsScreen(
                            channels = channels
                )
}
                )
}