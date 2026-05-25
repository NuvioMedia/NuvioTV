package com.nuvio.tv.iptv.ui.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.tv.iptv.data.model.IptvChannel
import androidx.compose.ui.tooling.preview.preview

@Composable
fun IptvChannelsScreen(
        channels: List<IptvChannel>
) {
        if (channels.isEmpty()) {
                    Column(
                                    modifier = Modifier
                                                    .fillMaxSize()
                                                                    .padding(24.dp),
                                                                                verticalArrangement = Arrangement.Center
                    ) {
                                    Text(text = "No IPTV channels loaded")
                    }
        } else {
                    LazyColumn(
                                    modifier = Modifier
                                                    .fillMaxSize()
                                                                    .padding(24.dp)
                    ) {
                                    items(channels) { channel ->
                                                    Text(
                                                                            text = channel.name,
                                                                                                modifier = Modifier.padding(vertical = 8.dp)
                                                    )
                                    }
                    }
        }
}
                                                    )}
                    }
                    )
        }
                    }
                    )
        }
}
)

@Preview(showBackground = true)
@Composable
fun IptvChannelsScreenPreview() {
            IptvChannelsScreen(
                        channels = listOf(
                                            IptvChannel(
                                                                id = "1",
                                                                                tvgId = "news1",
                                                                                                name = "News Channel",
                                                                                                                logoUrl = null,
                                                                                                                                groupTitle = "News",
                                                                                                                                                streamUrl = "https://example.com/live/news.m3u8"
                                            ),
                                                        IptvChannel(
                                                                                id = "2",
                                                                                                tvgId = "sports1",
                                                                                                                name = "Sports Channel",
                                                                                                                                logoUrl = null,
                                                                                                                                                groupTitle = "Sports",
                                                                                                                                                                streamUrl = "https://example.com/live/sports.m3u8"
                                                        ),
                                                                    IptvChannel(
                                                                                        id = "3",
                                                                                                        tvgId = "movie1",
                                                                                                                        name = "Movie Channel",
                                                                                                                                        logoUrl = null,
                                                                                                                                                        groupTitle = "Movies",
                                                                                                                                                                        streamUrl = "https://example.com/live/movies.m3u8"
                                                                    )
                        )
            )
}
                                                                    )
                                                        )
                                            )
                        )
            )
}