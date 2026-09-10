package com.nuvio.tv.core.plugin

internal object PluginSafety {
    fun isVideoEasyScraper(
        scraperId: String?,
        scraperName: String? = null,
        filename: String? = null
    ): Boolean {
        return listOf(scraperId, scraperName, filename).any { value ->
            value?.contains("videasy", ignoreCase = true) == true
        }
    }

    /**
     * StreamingCommunity's stream source (vixsrc.to/vixcloud.co) returns HTTP 403 on its
     * playlist pre-check for requests coming from known VPN exit IPs - confirmed against a
     * real ProtonVPN endpoint, and confirmed working with the user's real IP with the VPN off.
     * This is the destination site's own anti-VPN/geo block, not something fixable by changing
     * how NuvioTV talks to it, so this scraper's own traffic is explicitly routed around the
     * app-scoped VPN tunnel (see PluginRuntime's bypassVpnClient) while every other scraper -
     * and the rest of the app - keeps using the VPN when the user has it on.
     */
    fun shouldBypassVpn(
        scraperId: String?,
        scraperName: String? = null
    ): Boolean {
        return listOf(scraperId, scraperName).any { value ->
            value?.contains("streamingcommunity", ignoreCase = true) == true
        }
    }

    /**
     * Same anti-VPN block as [shouldBypassVpn], but applied at the actual video-playback
     * layer, where only the resolved stream URL is available (not the scraper that produced
     * it). Matches StreamingCommunity's known stream hosts (vixsrc.to/vixcloud.co) and the
     * unity redirect domains it resolves through, so the ExoPlayer HTTP data source bypasses
     * the VPN the same way PluginRuntime's scraper client does.
     */
    private val VPN_BYPASS_HOSTS = listOf(
        "vixsrc.to",
        "vixcloud.co",
        "streamingunity.vip",
        "streamingunity.win"
    )

    fun shouldBypassVpnForUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = try {
            java.net.URI(url).host
        } catch (_: Exception) {
            null
        }?.lowercase() ?: return false
        return VPN_BYPASS_HOSTS.any { bypassHost -> host == bypassHost || host.endsWith(".$bypassHost") }
    }
}
