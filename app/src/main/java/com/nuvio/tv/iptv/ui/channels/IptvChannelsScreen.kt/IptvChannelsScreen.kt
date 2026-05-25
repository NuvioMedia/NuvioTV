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
import androidx.compose.material3.Button
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.tv.iptv.data.model.IptvSampleData

@Composable
fun IptvChannelsScreen(
            channels: List<IptvChannel>
) {
            var message by remember { mutableStateOf("IPTV screen loaded") }

                Column(
                                modifier = Modifier
                                            .fillMaxSize()
                                                        .padding(24.dp),
                                                                verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                                Text(text = message)

                                        Button(
                                                            onClick = {
                                                                                message = "Button clicked"
                                                            }
                                        ) {
                                                            Text(text = "Test IPTV Button")
                                        }

                                                if (channels.isEmpty()) {
                                                                    Text(text = "No IPTV channels loaded")
                                                } else {
                                                                    LazyColumn {
                                                                                        items(channels) { channel ->
                                                                                                            Text(
                                                                                                                                        text = channel.name,
                                                                                                                                                                modifier = Modifier.padding(vertical = 8.dp)
                                                                                                            )
                                                                                        }
                                                                    }
                                                }
                }
}
                                                                                                            )}
                                                                    }
                                                }
                                                }
                                        }
                                                            }
                                        )
                }
                )
}
)

@Preview(showBackground = true)
@Composable
fun IptvChannelsScreenPreview() {
            IptvChannelsScreen(
                        channels = IptvSampleData.channels
            )
}
            )
}