package com.nuvio.tv.data.mediaserver.jellyfin

import com.nuvio.tv.data.mediaserver.mediabrowser.Endpoint
import com.nuvio.tv.data.mediaserver.mediabrowser.MediaBrowserProvider
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.nuvio.tv.data.mediaserver.mediabrowser.pathSegment
import okhttp3.OkHttpClient

internal class JellyfinProvider(
    http: OkHttpClient,
    identity: ServerClientIdentity
) : MediaBrowserProvider(authorizationHeader = "Authorization", apiPath = "", http = http, identity = identity) {
    override val id: String = "jellyfin"
    override val displayName: String = "Jellyfin"
    override val minimumVersion: String = "10.9"

    override fun viewsEndpoint(userId: String) = Endpoint("/UserViews", mapOf("userId" to userId))

    override fun itemsEndpoint(userId: String) = Endpoint("/Items", mapOf("userId" to userId))

    override fun itemEndpoint(userId: String, itemId: String) =
        Endpoint("/Items/${pathSegment(itemId)}", mapOf("userId" to userId))

    override fun resumeEndpoint(userId: String) = Endpoint("/UserItems/Resume", mapOf("userId" to userId))

    override fun playedEndpoint(userId: String, itemId: String) =
        Endpoint("/UserPlayedItems/${pathSegment(itemId)}", mapOf("userId" to userId))
}
