package com.nuvio.tv.data.mdblist

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListPrivacy
import com.nuvio.tv.domain.model.ListMembershipChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MdbListLiveLibraryTest {
    @Test
    fun privateStaticListLifecycle() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("mdblistLiveLibrary") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = MdbListAuthStore(AndroidMdbListAuthPersistence(context))
        store.selectProfile(arguments.getString("mdblistProfileId")?.toInt() ?: 1)
        assertTrue(store.state.value.isAuthenticated)
        val configuration = MdbListConfiguration(BuildConfig.MDBLIST_CLIENT_ID, BuildConfig.VERSION_NAME)
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
        val engine = OkHttpMdbListEngine(client, configuration)
        val name = "Library QA ${System.currentTimeMillis()}"
        var temporaryListId: Long? = null
        val records = mutableListOf<JsonObject>()
        val report = File(context.getExternalFilesDir(null), "mdblist-live-library-report.json")
        val http = MdbListHttpClient(MdbListHttpEngine { request ->
            engine.execute(request).also { response ->
                val body = runCatching { Json.parseToJsonElement(response.body) }.getOrNull()
                if (request.path == "/lists/user/add" && response.status in 200..299) {
                    temporaryListId = (body as? JsonObject)?.number("id")
                }
                if (request.path == "/lists/user/add" || request.path == "/lists/user" ||
                    temporaryListId?.let { request.path == "/lists/$it" || request.path.startsWith("/lists/$it/items") } == true) {
                    records += buildJsonObject {
                        put("method", request.method.name)
                        put("path", request.path)
                        put("status", response.status)
                        if (body != null) put("body", if (request.path == "/lists/user" && body is JsonArray) {
                            JsonArray(body.filter { it.objectValue().text("name")?.startsWith(name) == true })
                        } else body)
                    }
                    report.writeText(JsonArray(records).toString())
                }
            }
        })
        val auth = MdbListAuthRepository(http, configuration, store)
        val api = MdbListApiClient(http, auth, store)
        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val storage = object : MdbListSyncStorage {
            var saved: String? = null
            override suspend fun load(profileId: Int): String? = saved
            override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) { checkScope(); saved = payload }
            override suspend fun remove(profileId: Int, checkScope: () -> Unit) { checkScope(); saved = null }
        }
        val profile = MutableStateFlow(store.scope().profileId)
        val sync = MdbListSyncRepository(storage, store, api, profile, jobScope)
        val library = MdbListLibraryService(api, sync, store, profile, jobScope)
        val movie = LibraryEntryInput("tmdb:278", "movie", "The Shawshank Redemption", tmdbId = 278)
        val show = LibraryEntryInput("tmdb:1396", "series", "Breaking Bad", tmdbId = 1396)
        var watchlistAdded = false
        var deleted = false
        try {
            library.refresh(TrackingRefreshIntent.USER_INITIATED)
            library.listManager.createList(name, null, LibraryListPrivacy.PRIVATE)
            val created = sync.currentSnapshot()!!.library!!.lists.single { it.name == name }
            assertEquals(temporaryListId, created.id)
            assertTrue(created.private)
            val add = ListMembershipChanges(mapOf(created.key to true))
            library.applyMembershipChanges(movie, add)
            library.applyMembershipChanges(show, add)
            library.refresh(TrackingRefreshIntent.USER_INITIATED)
            val contents = sync.currentSnapshot()!!.library!!.itemsByList.getValue(created.key)
            assertEquals(setOf(MdbListItemType.MOVIE, MdbListItemType.SHOW), contents.map { it.type }.toSet())
            assertEquals(setOf(278L, 1396L), contents.map { it.media.ids.tmdb }.toSet())
            assertTrue(contents.all { !it.media.poster.isNullOrBlank() })
            assertTrue(library.getMembershipSnapshot(movie).listMembership.getValue(created.key))
            assertTrue(library.getMembershipSnapshot(show).listMembership.getValue(created.key))
            library.listManager.updateList(created.key, "$name renamed", null, LibraryListPrivacy.PRIVATE)
            library.refresh(TrackingRefreshIntent.USER_INITIATED)
            assertEquals("$name renamed", sync.currentSnapshot()!!.library!!.lists.single { it.id == created.id }.name)
            val remove = ListMembershipChanges(mapOf(created.key to false))
            library.applyMembershipChanges(movie, remove)
            library.applyMembershipChanges(show, remove)
            library.refresh(TrackingRefreshIntent.USER_INITIATED)
            assertTrue(sync.currentSnapshot()!!.library!!.itemsByList.getValue(created.key).isEmpty())
            if (arguments.getString("mdblistLiveWatchlist") == "true" &&
                !library.getMembershipSnapshot(movie).listMembership.getValue(MDBLIST_WATCHLIST_KEY)) {
                watchlistAdded = true
                library.applyMembershipChanges(movie, ListMembershipChanges(mapOf(MDBLIST_WATCHLIST_KEY to true)))
                library.refresh(TrackingRefreshIntent.USER_INITIATED)
                assertTrue(library.getMembershipSnapshot(movie).listMembership.getValue(MDBLIST_WATCHLIST_KEY))
                library.applyMembershipChanges(movie, ListMembershipChanges(mapOf(MDBLIST_WATCHLIST_KEY to false)))
                library.refresh(TrackingRefreshIntent.USER_INITIATED)
                assertFalse(library.getMembershipSnapshot(movie).listMembership.getValue(MDBLIST_WATCHLIST_KEY))
                watchlistAdded = false
            }
            library.listManager.deleteList(created.key)
            deleted = true
            library.refresh(TrackingRefreshIntent.USER_INITIATED)
            assertTrue(sync.currentSnapshot()!!.library!!.lists.none { it.id == created.id })
            assertFalse(sync.currentSnapshot()!!.library!!.itemsByList.containsKey(created.key))
        } finally {
            try {
                if (watchlistAdded) api.post("/watchlist/items/remove", movie.mdbListLibraryItem().membershipBody(true))
            } finally {
                try {
                    if (!deleted) temporaryListId?.let { api.delete("/lists/$it") }
                } finally {
                    jobScope.cancel()
                }
            }
        }
    }
}
