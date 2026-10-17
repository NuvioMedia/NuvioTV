package com.nuvio.tv.data.mdblist

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MdbListLiveScrobbleTest {
    @Test
    fun existingEpisodeProgressSurvivesPauseResumeAndStop() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("mdblistLiveScrobble") == "true")
        val imdb = requireNotNull(arguments.getString("mdblistShowImdb"))
        require(imdb.matches(Regex("tt[0-9]+")))
        val season = requireNotNull(arguments.getString("mdblistSeason")).toInt()
        val episode = requireNotNull(arguments.getString("mdblistEpisode")).toInt()
        val progress = requireNotNull(arguments.getString("mdblistProgress")).toDouble()
        require(season >= 0 && episode > 0 && progress.isFinite() && progress in 0.0..<80.0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = MdbListAuthStore(AndroidMdbListAuthPersistence(context))
        store.selectProfile(arguments.getString("mdblistProfileId")?.toInt() ?: 1)
        assertTrue(store.state.value.isAuthenticated)
        val configuration = MdbListConfiguration(BuildConfig.MDBLIST_CLIENT_ID, BuildConfig.VERSION_NAME)
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
        val engine = OkHttpMdbListEngine(client, configuration)
        val responses = mutableListOf<JsonObject>()
        val report = File(context.getExternalFilesDir(null), "mdblist-live-scrobble-report.json")
        val http = MdbListHttpClient(MdbListHttpEngine { request ->
            engine.execute(request).also { response ->
                if (request.path.startsWith("/scrobble/")) {
                    val body = runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()
                    responses += buildJsonObject {
                        put("path", request.path)
                        put("status", response.status)
                        put("sentProgress", Json.parseToJsonElement(request.body).jsonObject["progress"] ?: JsonNull)
                        for (key in listOf("action", "progress", "error", "detail", "message", "errors")) {
                            body?.get(key)?.let { put(key, it) }
                        }
                    }
                    report.writeText(JsonArray(responses).toString())
                }
            }
        })
        val auth = MdbListAuthRepository(http, configuration, store)
        val api = MdbListApiClient(http, auth, store)
        val target = MdbListMutationTarget(
            MdbListItemType.EPISODE, MdbListMedia(MdbListIds(imdb = imdb)), season, episode
        )
        var savedProgress = 0f
        for (action in listOf(TrackingScrobbleAction.START, TrackingScrobbleAction.PAUSE,
            TrackingScrobbleAction.START, TrackingScrobbleAction.STOP)) {
            val response = api.post("/scrobble/${action.wireValue}", target.scrobbleBody(progress).toString(), store.scope())
            val receipt = decodeMdbListScrobbleReceipt(response, target, action, System.currentTimeMillis())
            assertTrue(!receipt.isWatched && receipt.progress < 80f)
            savedProgress = receipt.progress
        }
        val paused = MdbListHttpSyncRemote(api, store.scope()).playback().singleOrNull(target::matches)
        responses += buildJsonObject {
            put("path", "/sync/playback")
            put("matchedSession", paused != null)
            paused?.let { put("progress", it.progress) }
        }
        report.writeText(JsonArray(responses).toString())
        assertTrue(paused != null)
        assertEquals(savedProgress, paused!!.progress, 0.001f)
    }
}
