package com.nuvio.tv.data.mdblist

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MdbListLiveAccountTest {
    @Test
    fun approvedDeviceSessionRefreshAndReadSync() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mdblistLiveRead") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = MdbListAuthStore(AndroidMdbListAuthPersistence(context))
        val configuration = MdbListConfiguration(BuildConfig.MDBLIST_CLIENT_ID, BuildConfig.VERSION_NAME)
        val engine = OkHttpMdbListEngine(
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS).build(), configuration
        )
        val responses = mutableListOf<JsonObject>()
        val report = File(context.getExternalFilesDir(null), "mdblist-live-read-report.json")
        val http = MdbListHttpClient(MdbListHttpEngine { request ->
            engine.execute(request).also { response ->
                if (request.method == MdbListHttpMethod.GET) {
                    responses += buildJsonObject {
                        put("path", request.path)
                        put("status", response.status)
                        put("shape", shape(Json.parseToJsonElement(response.body)))
                        if (request.path == "/sync/last_activities") {
                            put("serverTime", Json.parseToJsonElement(response.body).jsonObject["server_time"] ?: JsonNull)
                        }
                    }
                    report.writeText(JsonArray(responses).toString())
                }
            }
        })
        val auth = MdbListAuthRepository(http, configuration, store)
        if (!store.state.value.isAuthenticated) {
            val deviceFile = File(context.getExternalFilesDir(null), "mdblist-live-device.json")
            val device = Json.parseToJsonElement(deviceFile.readText()).jsonObject
            val scope = store.scope()
            val now = System.currentTimeMillis()
            store.saveSession(
                MdbListDeviceSession(
                    device.getValue("user_code").jsonPrimitive.content,
                    device.getValue("verification_uri").jsonPrimitive.content,
                    device.getValue("verification_uri_complete").jsonPrimitive.content,
                    now + device.getValue("expires_in").jsonPrimitive.long * 1_000,
                    device.getValue("interval").jsonPrimitive.long.toInt(), 0
                ),
                device.getValue("device_code").jsonPrimitive.content, scope
            )
            assertTrue(auth.pollDeviceAuthorization() == MdbListDevicePollResult.Authorized)
            deviceFile.delete()
        }
        val scope = store.scope()
        val current = store.authorization()!!
        val refreshed = auth.authorization(scope, rejectedAccessToken = current.tokens.accessToken)
        assertTrue(refreshed.tokens.expiresAtEpochMs > System.currentTimeMillis())
        val api = MdbListApiClient(http, auth, store)
        val user = api.refreshUser(scope)
        val remote = MdbListHttpSyncRemote(api, scope)
        val snapshot = MdbListSyncEngine(remote).synchronize(MdbListSyncSnapshot(user.id!!))
        assertTrue(snapshot.isInitialized)
        val projection = MdbListProgressProjection(snapshot)
        val journal = snapshot.watermark?.let { remote.journal(it) }
        report.writeText(buildJsonObject {
            put("authorized", store.state.value.isAuthenticated)
            put("refreshed", true)
            put("watched", snapshot.watched.size)
            put("paused", snapshot.playback.size)
            put("dropped", snapshot.dropped.size)
            put("continueWatching", projection.progress.size)
            put("nextUpSeeds", projection.nextUp(true).size)
            put("journalItems", journal?.items?.size ?: 0)
            put("responses", JsonArray(responses))
        }.toString())
    }

    private fun shape(element: JsonElement): JsonElement = when (element) {
        JsonNull -> JsonPrimitive("null")
        is JsonObject -> JsonObject(element.mapValues { shape(it.value) })
        is JsonArray -> JsonArray(element.take(3).map(::shape))
        is JsonPrimitive -> JsonPrimitive(if (element.isString) "string" else if (element.contentOrNull in setOf("true", "false")) "boolean" else "number")
    }
}
