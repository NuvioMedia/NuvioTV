package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerPlaybackEvent
import com.nuvio.tv.data.mediaserver.ServerPlaybackEventType
import com.nuvio.tv.data.mediaserver.ServerPlaybackRequest
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerPlayerCapabilities
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.emby.EmbyProvider
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.runTest

class MediaBrowserRequestTest {
    private fun session(providerId: String, address: String) = ServerSession(
        connection = ServerConnection(
            id = "cabc",
            providerId = providerId,
            name = "Home",
            address = address,
            remoteServerId = "s1",
            remoteUserId = "u1",
            userName = "viewer",
            credentialRef = "k1",
            libraries = listOf(ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE))
        ),
        token = "secret"
    )

    @Test
    fun embySignInUsesApiRootAndEmbyHeader() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/emby/System/Info/Public" -> """{"ServerName": "Den", "Version": "4.8.10.0", "Id": "srv"}"""
                "/emby/Users/AuthenticateByName" -> """{"User": {"Id": "u1", "Name": "viewer"}, "AccessToken": "tok", "ServerId": "srv"}"""
                else -> error("Unexpected ${request.url}")
            }
        }
        val signIn = EmbyProvider(http.client, testIdentity).signIn("http://den.local:8096/emby/web/index.html", "viewer", "pw")

        assertEquals("http://den.local:8096", signIn.address)
        assertEquals("Den", signIn.serverName)
        assertEquals("srv", signIn.serverId)
        assertEquals("u1", signIn.userId)
        assertEquals("tok", signIn.token)
        val auth = http.requests.last()
        assertEquals("POST", auth.method)
        assertTrue(auth.text.contains("\"Pw\":\"pw\""))
        val header = auth.header("X-Emby-Authorization").orEmpty()
        assertTrue(header.startsWith("MediaBrowser Client=\"Nuvio\""))
        assertFalse(header.contains("Token="))
        assertNull(auth.header("Authorization"))
    }

    @Test
    fun embyRejectsJellyfinServers() = runTest {
        val http = TestHttp { """{"ServerName": "Den", "Version": "10.10.7", "ProductName": "Jellyfin Server", "Id": "srv"}""" }
        val error = runCatching {
            EmbyProvider(http.client, testIdentity).signIn("http://den.local:8096", "viewer", "pw")
        }.exceptionOrNull() as ServerException
        assertEquals(ServerFailure.UNSUPPORTED, error.failure)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun embyUsesUserScopedRoutes() = runTest {
        val http = TestHttp { request ->
            when {
                request.url.encodedPath.endsWith("/Views") ->
                    """{"Items": [{"Id": "lib1", "Name": "Movies", "CollectionType": "movies"}, {"Id": "m", "Name": "Music", "CollectionType": "music"}]}"""
                request.url.encodedPath.endsWith("/Items/Resume") -> """{"Items": []}"""
                else -> ""
            }
        }
        val emby = EmbyProvider(http.client, testIdentity)
        val session = session("emby", "https://media.example.com")

        val libraries = emby.libraries(session)
        emby.resumeItems(session, limit = 10)
        emby.setPlayed(session, "i1", played = true)
        emby.setPlayed(session, "i1", played = false)

        assertEquals(listOf(ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE)), libraries)
        assertEquals(
            listOf(
                "GET https://media.example.com/emby/Users/u1/Views",
                "GET https://media.example.com/emby/Users/u1/Items/Resume",
                "POST https://media.example.com/emby/Users/u1/PlayedItems/i1",
                "DELETE https://media.example.com/emby/Users/u1/PlayedItems/i1"
            ),
            http.requests.map { "${it.method} ${it.url.toString().substringBefore('?')}" }
        )
        http.requests.forEach { request ->
            assertTrue(request.header("X-Emby-Authorization").orEmpty().endsWith("Token=\"secret\""))
            assertNull(request.url.queryParameter("userId"))
        }
    }

    @Test
    fun jellyfinUsesQueryScopedRoutes() = runTest {
        val http = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/UserViews")) """{"Items": []}""" else ""
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com/jellyfin")

        jellyfin.libraries(session)
        jellyfin.setPlayed(session, "i1", played = true)

        assertEquals(
            listOf(
                "GET https://media.example.com/jellyfin/UserViews?userId=u1",
                "POST https://media.example.com/jellyfin/UserPlayedItems/i1?userId=u1"
            ),
            http.requests.map { "${it.method} ${it.url}" }
        )
        http.requests.forEach { request ->
            assertTrue(request.header("Authorization").orEmpty().endsWith("Token=\"secret\""))
            assertNull(request.header("X-Emby-Authorization"))
        }
    }

    @Test
    fun offersEveryFormatUntilDirectPlayFails() = runTest {
        val http = TestHttp {
            """{"PlaySessionId": "ps1", "MediaSources": [{"Id": "ms1", "SupportsDirectPlay": true, "TranscodingUrl": "/videos/i1/master.m3u8"}]}"""
        }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val session = session("jellyfin", "https://media.example.com/jellyfin")
        val target = ServerPlaybackTarget(ServerItemRef("cabc", "i1"), mediaSourceId = "ms1")

        jellyfin.preparePlayback(session, ServerPlaybackRequest(target, ServerPlayerCapabilities()))
        jellyfin.preparePlayback(session, ServerPlaybackRequest(target, ServerPlayerCapabilities(allowDirectPlay = false)))

        val (direct, fallback) = http.requests
        assertEquals("1000000000", direct.url.queryParameter("maxStreamingBitrate"))
        assertTrue(direct.text.contains("\"MaxStreamingBitrate\":1000000000"))
        assertTrue(direct.text.contains("\"DirectPlayProfiles\":[{\"Type\":\"Video\"}]"))
        assertFalse(fallback.text.contains("\"DirectPlayProfiles\":[{\"Type\":\"Video\"}]"))
        assertTrue(fallback.text.contains("\"AudioCodec\":\"aac,mp3,ac3,eac3,flac,opus,vorbis\""))
    }

    @Test
    fun transcodesToHevcWithDolbyAudioAndBurnsInPgs() = runTest {
        val http = TestHttp { """{"PlaySessionId": "ps1", "MediaSources": [{"Id": "ms1", "TranscodingUrl": "/videos/i1/master.m3u8"}]}""" }
        val jellyfin = JellyfinProvider(http.client, testIdentity)
        val target = ServerPlaybackTarget(ServerItemRef("cabc", "i1"), mediaSourceId = "ms1")

        jellyfin.preparePlayback(
            session("jellyfin", "https://media.example.com/jellyfin"),
            ServerPlaybackRequest(target, ServerPlayerCapabilities(allowDirectPlay = false), subtitleStreamIndex = 3)
        )

        val request = http.requests.single()
        assertEquals("3", request.url.queryParameter("subtitleStreamIndex"))
        assertTrue(request.text.contains("\"SubtitleStreamIndex\":3"))
        assertTrue(request.text.contains("\"VideoCodec\":\"hevc,h264\",\"AudioCodec\":\"ac3,eac3,aac,mp3\""))
        assertTrue(request.text.contains("\"MaxAudioChannels\":\"8\""))
        assertTrue(request.text.contains("{\"Format\":\"pgssub\",\"Method\":\"Encode\"}"))
    }

    @Test
    fun embyPreparesAndReportsPlaybackThroughApiRoot() = runTest {
        val http = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/PlaybackInfo")) {
                """{"PlaySessionId": "ps1", "MediaSources": [{"Id": "ms1", "SupportsDirectPlay": true}]}"""
            } else {
                ""
            }
        }
        val emby = EmbyProvider(http.client, testIdentity)
        val session = session("emby", "https://media.example.com")
        val playback = emby.preparePlayback(
            session,
            ServerPlaybackRequest(
                target = ServerPlaybackTarget(ServerItemRef("cabc", "i1"), mediaSourceId = "ms1"),
                capabilities = ServerPlayerCapabilities(),
                audioStreamIndex = 2
            )
        )
        emby.report(session, playback, ServerPlaybackEvent(ServerPlaybackEventType.PROGRESS, positionMs = 1_500, isPaused = false))

        val info = http.requests[0]
        assertEquals("/emby/Items/i1/PlaybackInfo", info.url.encodedPath)
        assertEquals("u1", info.url.queryParameter("userId"))
        assertEquals("ms1", info.url.queryParameter("mediaSourceId"))
        assertEquals("2", info.url.queryParameter("audioStreamIndex"))
        assertTrue(info.text.contains("\"AudioStreamIndex\":2"))
        assertTrue(info.text.contains("{\"Format\":\"subrip\",\"Method\":\"Embed\"}"))
        assertTrue(info.text.contains("{\"Format\":\"subrip\",\"Method\":\"External\"}"))
        assertTrue(playback.url.startsWith("https://media.example.com/emby/Videos/i1/stream?static=true"))
        val report = http.requests[1]
        assertEquals("/emby/Sessions/Playing/Progress", report.url.encodedPath)
        assertTrue(report.text.contains("\"PositionTicks\":15000000"))
        assertTrue(report.text.contains("\"EventName\":\"TimeUpdate\""))
        assertTrue(report.text.contains("\"PlaySessionId\":\"ps1\""))
    }
}
