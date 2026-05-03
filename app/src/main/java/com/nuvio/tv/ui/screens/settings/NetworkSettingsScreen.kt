@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
private interface ClearCwCacheEntryPoint {
    fun cwEnrichmentCache(): com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
private interface ProfileManagerEntryPoint {
    fun profileManager(): com.nuvio.tv.core.profile.ProfileManager
}

private enum class NetworkTestState { Idle, TestingLatency, TestingDownload, Done, Error }

private enum class ConnectionType { WiFi, Ethernet, Offline }

private fun getConnectionType(context: android.content.Context): ConnectionType {
    val cm = context.getSystemService<ConnectivityManager>() ?: return ConnectionType.Offline
    val network = cm.activeNetwork ?: return ConnectionType.Offline
    val caps = cm.getNetworkCapabilities(network) ?: return ConnectionType.Offline
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.Ethernet
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WiFi
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.WiFi // treat cellular as connected
        else -> ConnectionType.Offline
    }
}

@Composable
private fun ConnectionStatusBadge(type: ConnectionType) {
    val (icon, label, color) = when (type) {
        ConnectionType.WiFi -> Triple(Icons.Default.Wifi, stringResource(R.string.network_connection_wifi), NuvioColors.Success)
        ConnectionType.Ethernet -> Triple(Icons.Default.Wifi, stringResource(R.string.network_connection_ethernet), NuvioColors.Success)
        ConnectionType.Offline -> Triple(Icons.Default.SignalWifiOff, stringResource(R.string.network_connection_offline), NuvioColors.Error)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = color
        )
    }
}

private suspend fun fetchFastComUrls(): List<String> = withContext(Dispatchers.IO) {
    // 1. Load fast.com page to find the app JS bundle URL
    val html = (URL("https://fast.com").openConnection() as HttpURLConnection).run {
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "Mozilla/5.0")
        inputStream.bufferedReader().use { it.readText() }.also { disconnect() }
    }
    val scriptPath = Regex("""<script src="(/app[^"]+\.js)"""").find(html)?.groupValues?.get(1)
        ?: throw Exception("fast.com: script path not found")

    // 2. Extract the API token from the JS bundle
    val js = (URL("https://fast.com$scriptPath").openConnection() as HttpURLConnection).run {
        connectTimeout = 10_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Mozilla/5.0")
        inputStream.bufferedReader().use { it.readText() }.also { disconnect() }
    }
    val token = Regex("""token:"([^"]+)"""").find(js)?.groupValues?.get(1)
        ?: throw Exception("fast.com: token not found in bundle")

    // 3. Fetch CDN URLs from the speed-test API
    val apiJson = (URL("https://api.fast.com/netflix/speedtest/v2?https=true&token=$token&urlCount=15")
        .openConnection() as HttpURLConnection).run {
        connectTimeout = 5_000
        readTimeout = 10_000
        setRequestProperty("User-Agent", "Mozilla/5.0")
        inputStream.bufferedReader().use { it.readText() }.also { disconnect() }
    }
    val targets = JSONObject(apiJson).getJSONArray("targets")
    (0 until targets.length()).map { targets.getJSONObject(it).getString("url") }
}

@Composable
fun AdvancedSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    viewModel: AdvancedSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var connectionType by remember { mutableStateOf(getConnectionType(context)) }
    var testState by remember { mutableStateOf(NetworkTestState.Idle) }
    var latencyMs by remember { mutableStateOf<Long?>(null) }
    var downloadMbps by remember { mutableStateOf<Double?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    fun runSpeedTest() {
        scope.launch {
            connectionType = getConnectionType(context)
            testState = NetworkTestState.TestingLatency
            latencyMs = null
            downloadMbps = null
            errorMessage = null

            try {
                // ── Latency: average 3 round-trips to Cloudflare ─────────────
                var totalMs = 0L
                withContext(Dispatchers.IO) {
                    repeat(3) {
                        val conn = URL("https://cloudflare.com/cdn-cgi/trace")
                            .openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 5_000
                        conn.readTimeout = 5_000
                        val t0 = System.currentTimeMillis()
                        conn.connect()
                        conn.inputStream.use { it.read() }
                        totalMs += System.currentTimeMillis() - t0
                        conn.disconnect()
                    }
                }
                latencyMs = totalMs / 3

                // ── Download: parallel streams from fast.com for 10 s ────────
                testState = NetworkTestState.TestingDownload
                val (totalBytes, elapsed) = withContext(Dispatchers.IO) {
                    val urls = fetchFastComUrls()
                    val deadline = System.currentTimeMillis() + 10_000L
                    val startTime = System.currentTimeMillis()

                    // Open 4 connections per URL → 60 parallel streams total
                    val streams = urls.flatMap { url -> List(4) { url } }
                    coroutineScope {
                        val jobs = streams.map { url ->
                            async {
                                var bytes = 0L
                                val buf = ByteArray(65536)
                                try {
                                    val conn = URL(url).openConnection() as HttpURLConnection
                                    conn.connectTimeout = 5_000
                                    conn.readTimeout = 15_000
                                    conn.connect()
                                    conn.inputStream.use { stream ->
                                        var read: Int = 0
                                        while (System.currentTimeMillis() < deadline &&
                                            stream.read(buf).also { read = it } != -1
                                        ) {
                                            bytes += read
                                        }
                                    }
                                    conn.disconnect()
                                } catch (_: Exception) {}
                                bytes
                            }
                        }
                        val total = jobs.awaitAll().sum()
                        Pair(total, System.currentTimeMillis() - startTime)
                    }
                }
                downloadMbps = if (elapsed > 0) (totalBytes * 8.0) / (elapsed * 1000.0) else 0.0
                testState = NetworkTestState.Done

            } catch (e: Exception) {
                errorMessage = e.localizedMessage ?: "Unknown error"
                testState = NetworkTestState.Error
            }
        }
    }

    val networkListState = rememberLazyListState()
    val performanceFocusRequester = remember { initialFocusRequester ?: FocusRequester() }
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = networkListState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SettingsDetailHeader(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.settings_advanced),
                    subtitle = stringResource(R.string.settings_advanced_subtitle)
                )
                AnimatedVisibility(
                    visible = testState != NetworkTestState.Idle,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    ConnectionStatusBadge(type = connectionType)
                }
            }
        }

        item(key = "performance_header") {
            Text(
                text = stringResource(R.string.advanced_section_performance),
                style = MaterialTheme.typography.titleSmall,
                color = NuvioColors.TextTertiary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        item(key = "performance_settings") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                LaunchedEffect(Unit) {
                    if (initialFocusRequester != null) {
                        runCatching { performanceFocusRequester.requestFocus() }
                    }
                }
                SettingsToggleRow(
                    title = stringResource(R.string.advanced_fast_horizontal_navigation),
                    subtitle = stringResource(R.string.advanced_fast_horizontal_navigation_subtitle),
                    checked = uiState.fastHorizontalNavigationEnabled,
                    onToggle = {
                        viewModel.onEvent(
                            AdvancedSettingsEvent.SetFastHorizontalNavigationEnabled(
                                !uiState.fastHorizontalNavigationEnabled
                            )
                        )
                    },
                    modifier = Modifier.focusRequester(performanceFocusRequester)
                )
                SettingsToggleRow(
                    title = stringResource(R.string.advanced_nuvio_focus_scroll),
                    subtitle = stringResource(R.string.advanced_nuvio_focus_scroll_subtitle),
                    checked = uiState.smoothBringIntoViewEnabled,
                    onToggle = {
                        viewModel.onEvent(
                            AdvancedSettingsEvent.SetSmoothBringIntoViewEnabled(
                                !uiState.smoothBringIntoViewEnabled
                            )
                        )
                    }
                )
                SettingsToggleRow(
                    title = stringResource(R.string.advanced_memory_only_vertical_scroll),
                    subtitle = stringResource(R.string.advanced_memory_only_vertical_scroll_subtitle),
                    checked = uiState.memoryOnlyVerticalScroll,
                    onToggle = {
                        viewModel.onEvent(
                            AdvancedSettingsEvent.SetMemoryOnlyVerticalScroll(
                                !uiState.memoryOnlyVerticalScroll
                            )
                        )
                    }
                )
                val profileManager = remember {
                    dagger.hilt.android.EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        ProfileManagerEntryPoint::class.java
                    ).profileManager()
                }
                val rememberLastProfileEnabled by profileManager.rememberLastProfileEnabled.collectAsState()
                SettingsToggleRow(
                    title = stringResource(R.string.advanced_remember_last_profile),
                    subtitle = stringResource(R.string.advanced_remember_last_profile_subtitle),
                    checked = rememberLastProfileEnabled,
                    onToggle = {
                        scope.launch {
                            profileManager.setRememberLastProfileEnabled(!rememberLastProfileEnabled)
                        }
                    }
                )
            }
        }

        item(key = "diagnostics_header") {
            Text(
                text = stringResource(R.string.advanced_section_diagnostics),
                style = MaterialTheme.typography.titleSmall,
                color = NuvioColors.TextTertiary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        item(key = "speed_test") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                val isRunning = testState == NetworkTestState.TestingLatency ||
                        testState == NetworkTestState.TestingDownload
                SettingsActionRow(
                    title = stringResource(
                        if (isRunning) R.string.network_speed_test_running
                        else R.string.network_speed_test_run
                    ),
                    subtitle = stringResource(R.string.network_speed_test_subtitle),
                    value = if (isRunning) stringResource(
                        when (testState) {
                            NetworkTestState.TestingLatency -> R.string.network_testing_latency
                            else -> R.string.network_testing_download
                        }
                    ) else null,
                    onClick = { if (!isRunning) runSpeedTest() }
                )
            }
        }

        if (testState != NetworkTestState.Idle) {
            item(key = "speed_results") {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.network_results_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = NuvioColors.TextSecondary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            NetworkMetricCard(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.Timer,
                                label = stringResource(R.string.network_latency_label),
                                value = latencyMs?.let { "$it ms" },
                                loading = testState == NetworkTestState.TestingLatency
                            )
                            NetworkMetricCard(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.Speed,
                                label = stringResource(R.string.network_download_label),
                                value = downloadMbps?.let { "%.1f Mbps".format(it) },
                                loading = testState == NetworkTestState.TestingDownload
                            )
                        }

                        if (testState == NetworkTestState.Error && errorMessage != null) {
                            Text(
                                text = stringResource(R.string.network_error_prefix, errorMessage!!),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioColors.Error
                            )
                        }

                        Text(
                            text = stringResource(R.string.network_powered_by_fast),
                            style = MaterialTheme.typography.labelSmall,
                            color = NuvioColors.TextSecondary.copy(alpha = 0.45f)
                        )
                    }
                }
            }
        }

        item(key = "cache_header") {
            Text(
                text = stringResource(R.string.advanced_section_cache),
                style = MaterialTheme.typography.titleSmall,
                color = NuvioColors.TextTertiary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        item(key = "clear_cw_cache") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                var cleared by remember { mutableStateOf(false) }
                SettingsActionRow(
                    title = stringResource(R.string.advanced_clear_cw_cache),
                    subtitle = if (cleared) {
                        stringResource(R.string.advanced_clear_cw_cache_done)
                    } else {
                        stringResource(R.string.advanced_clear_cw_cache_subtitle)
                    },
                    onClick = {
                        if (!cleared) {
                            scope.launch {
                                val entryPoint = dagger.hilt.android.EntryPointAccessors
                                    .fromApplication(
                                        context.applicationContext,
                                        ClearCwCacheEntryPoint::class.java
                                    )
                                entryPoint.cwEnrichmentCache().clearAll()
                                cleared = true
                            }
                        }
                    }
                )
            }
        }
    }
        SettingsVerticalScrollIndicators(state = networkListState)
    }
}

@Composable
private fun NetworkMetricCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String?,
    loading: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Column(
        modifier = modifier.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (loading) Icons.Default.Refresh else icon,
            contentDescription = null,
            modifier = Modifier
                .size(28.dp)
                .then(if (loading) Modifier.rotate(rotation) else Modifier),
            tint = if (loading) NuvioColors.Secondary else NuvioColors.Primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextSecondary
        )
        Text(
            text = when {
                loading -> "..."
                value != null -> value
                else -> "–"
            },
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = if (value != null && !loading) NuvioColors.TextPrimary else NuvioColors.TextTertiary
        )
    }
}
