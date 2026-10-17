package com.nuvio.tv.data.mediaserver.emby

import com.nuvio.tv.data.mediaserver.mediabrowser.Endpoint
import com.nuvio.tv.data.mediaserver.mediabrowser.MediaBrowserProvider
import com.nuvio.tv.data.mediaserver.mediabrowser.PublicInfo
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.nuvio.tv.data.mediaserver.mediabrowser.pathSegment
import okhttp3.OkHttpClient

internal class EmbyProvider(
    http: OkHttpClient,
    identity: ServerClientIdentity
) : MediaBrowserProvider(authorizationHeader = "X-Emby-Authorization", apiPath = "/emby", http = http, identity = identity) {
    override val id: String = "emby"
    override val displayName: String = "Emby"
    override val minimumVersion: String = "4.7"

    override fun isSupported(info: PublicInfo): Boolean =
        info.productName?.contains("Jellyfin", ignoreCase = true) != true && isSupportedVersion(info.version)

    override fun viewsEndpoint(userId: String) = Endpoint("/Users/${pathSegment(userId)}/Views")

    override fun itemsEndpoint(userId: String) = Endpoint("/Users/${pathSegment(userId)}/Items")

    override fun itemEndpoint(userId: String, itemId: String) =
        Endpoint("/Users/${pathSegment(userId)}/Items/${pathSegment(itemId)}")

    override fun resumeEndpoint(userId: String) = Endpoint("/Users/${pathSegment(userId)}/Items/Resume")

    override fun playedEndpoint(userId: String, itemId: String) =
        Endpoint("/Users/${pathSegment(userId)}/PlayedItems/${pathSegment(itemId)}")
}
