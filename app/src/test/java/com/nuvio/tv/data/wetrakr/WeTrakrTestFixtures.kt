package com.nuvio.tv.data.wetrakr

internal class WeTrakrTestPersistence : WeTrakrAuthPersistence {
    val profiles = mutableMapOf<Int, String>()

    override fun read(profileId: Int): String? = profiles[profileId]
    override fun write(profileId: Int, value: String?) {
        if (value == null) profiles.remove(profileId) else profiles[profileId] = value
    }
    override fun clear() = profiles.clear()
}

internal class WeTrakrTestEngine : WeTrakrHttpEngine {
    val requests = mutableListOf<WeTrakrHttpRequest>()
    val responses = ArrayDeque<WeTrakrHttpResponse>()

    override suspend fun execute(request: WeTrakrHttpRequest): WeTrakrHttpResponse {
        requests += request
        return responses.removeFirst()
    }
}

internal class WeTrakrTestHarness {
    var now = 1_790_000_000_000L
    val sleeps = mutableListOf<Long>()
    val store = WeTrakrAuthStore(WeTrakrTestPersistence())
    val engine = WeTrakrTestEngine()
    val configuration = WeTrakrConfiguration("public-client", "test")
    val http = WeTrakrHttpClient(engine, { now }, { sleeps += it; now += it })
    val auth = WeTrakrAuthRepository(http, configuration, store) { now }
    val api = WeTrakrApiClient(http, auth, store)

    fun reply(status: Int = 200, body: String = "{}", headers: Map<String, String> = emptyMap()) {
        engine.responses += WeTrakrHttpResponse(status, body, headers)
    }

    fun connected(accessToken: String = "access-one", refreshToken: String = "refresh-one") {
        store.authorize(WeTrakrTokens(accessToken, refreshToken, now + 7L * 24 * 60 * 60 * 1_000), store.scope())
    }

    suspend fun pending(): WeTrakrDeviceSession {
        reply(body = DEVICE_RESPONSE)
        return auth.startDeviceAuthorization()
    }

    companion object {
        const val DEVICE_RESPONSE = """{"device_code":"device-secret","user_code":"K7P2QX","verification_url":"https://wetrakr.com/activate","expires_in":600,"interval":5}"""
        const val TOKEN_RESPONSE = """{"access_token":"access-two","refresh_token":"refresh-two","token_type":"Bearer","expires_in":604800}"""
    }
}

internal class WeTrakrTestRemote(
    var activities: WeTrakrActivities = WeTrakrActivities(),
    val watched: MutableMap<WeTrakrItemType, List<WeTrakrWatchedRecord>> = mutableMapOf(),
    val playback: MutableMap<WeTrakrItemType, List<WeTrakrPlayback>> = mutableMapOf(),
    val watchlist: MutableMap<WeTrakrItemType, List<WeTrakrWatchlistItem>> = mutableMapOf()
) : WeTrakrSyncRemote {
    val calls = mutableListOf<String>()

    override suspend fun activities(): WeTrakrActivities {
        calls += "activities"
        return activities
    }

    override suspend fun watched(type: WeTrakrItemType, since: String?): List<WeTrakrWatchedRecord> {
        calls += "watched:$type:${since ?: "all"}"
        return watched[type].orEmpty()
    }

    override suspend fun playback(type: WeTrakrItemType): List<WeTrakrPlayback> {
        calls += "playback:$type"
        return playback[type].orEmpty()
    }

    override suspend fun watchlist(type: WeTrakrItemType): List<WeTrakrWatchlistItem> {
        calls += "watchlist:$type"
        return watchlist[type].orEmpty()
    }
}

internal fun weTrakrMovie(imdb: String, title: String = imdb) = WeTrakrMedia(WeTrakrIds(imdb = imdb), title = title)

internal suspend inline fun <reified T : Throwable> expectWeTrakrFailure(block: suspend () -> Unit): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) return error
        throw AssertionError("Expected ${T::class.simpleName}, got ${error::class.simpleName}", error)
    }
    throw AssertionError("Expected ${T::class.simpleName}")
}
