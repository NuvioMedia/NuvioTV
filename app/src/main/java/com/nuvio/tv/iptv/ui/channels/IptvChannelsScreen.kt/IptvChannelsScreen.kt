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