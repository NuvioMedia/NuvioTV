package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.emby.EmbyProvider
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerItemRef
import com.nuvio.tv.data.mediaserver.ServerPlayMethod
import com.nuvio.tv.data.mediaserver.ServerPlaybackRequest
import com.nuvio.tv.data.mediaserver.ServerPlaybackTarget
import com.nuvio.tv.data.mediaserver.ServerPlayerCapabilities
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.readableTranscodeReason
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

class MediaBrowserPlaybackTest {
    private val jellyfin = JellyfinProvider(TestHttp().client, testIdentity)
    private val emby = EmbyProvider(TestHttp().client, testIdentity)
    private val json = Json { ignoreUnknownKeys = true }
    private val session = ServerSession(
        connection = ServerConnection(
            id = "cabc",
            providerId = "jellyfin",
            name = "Home",
            address = "https://media.example.com/jellyfin",
            remoteServerId = "s1",
            remoteUserId = "u1",
            userName = "viewer",
            credentialRef = "k1"
        ),
        token = "secret"
    )
    private val request = ServerPlaybackRequest(
        target = ServerPlaybackTarget(ServerItemRef("cabc", "item1"), mediaSourceId = "ms1"),
        capabilities = ServerPlayerCapabilities()
    )

    private fun info(source: String) = json.decodeFromString(
        PlaybackInfoResult.serializer(),
        """{"PlaySessionId": "ps1", "MediaSources": [$source]}"""
    )

    @Test
    fun directPlayUsesStaticStreamOnOwningServer() {
        val playback = jellyfin.playbackSession(
            session,
            request,
            info(
                """{"Id": "ms1", "SupportsDirectPlay": true, "ETag": "e1",
                   "MediaStreams": [{"Type": "Subtitle", "Language": "eng", "DisplayTitle": "English", "DeliveryMethod": "External",
                                     "DeliveryUrl": "/Videos/item1/ms1/Subtitles/3/0/Stream.srt"}]}"""
            ),
            deviceId = "d1"
        )
        assertEquals(ServerPlayMethod.DIRECT_PLAY, playback.playMethod)
        assertTrue(playback.url.startsWith("https://media.example.com/jellyfin/Videos/item1/stream?static=true&mediaSourceId=ms1&playSessionId=ps1"))
        assertEquals("ps1", playback.playSessionId)
        assertEquals("https://media.example.com/jellyfin/Videos/item1/ms1/Subtitles/3/0/Stream.srt?api_key=secret", playback.subtitles.single().url)
        assertEquals("eng", playback.subtitles.single().lang)
    }

    @Test
    fun fallsBackToServerTranscodeWithoutDuplicatingKey() {
        val playback = jellyfin.playbackSession(
            session,
            request.copy(capabilities = ServerPlayerCapabilities(allowDirectPlay = false)),
            info("""{"Id": "ms1", "SupportsDirectPlay": true, "TranscodingUrl": "/videos/item1/master.m3u8?MediaSourceId=ms1&ApiKey=secret"}"""),
            deviceId = "d1"
        )
        assertEquals(ServerPlayMethod.TRANSCODE, playback.playMethod)
        assertEquals("https://media.example.com/jellyfin/videos/item1/master.m3u8?MediaSourceId=ms1&ApiKey=secret", playback.url)
    }

    @Test
    fun marksRemuxAsDirectStream() {
        val playback = jellyfin.playbackSession(
            session,
            request,
            info("""{"Id": "ms1", "SupportsDirectStream": true, "TranscodingUrl": "/videos/item1/master.m3u8?MediaSourceId=ms1"}"""),
            deviceId = "d1"
        )
        assertEquals(ServerPlayMethod.DIRECT_STREAM, playback.playMethod)
        assertTrue(playback.url.endsWith("&api_key=secret"))
    }

    @Test
    fun reportsPermissionAndCompatibilityFailures() {
        val denied = assertThrows(ServerException::class.java) {
            jellyfin.playbackSession(session, request, json.decodeFromString(PlaybackInfoResult.serializer(), """{"ErrorCode": "NotAllowed"}"""), "d1")
        }
        assertEquals(ServerFailure.FORBIDDEN, denied.failure)
        val unsupported = assertThrows(ServerException::class.java) {
            jellyfin.playbackSession(session, request, info("""{"Id": "ms1"}"""), "d1")
        }
        assertEquals(ServerFailure.UNSUPPORTED, unsupported.failure)
    }

    private val embySession = ServerSession(
        connection = session.connection.copy(providerId = "emby", address = "https://media.example.com"),
        token = "secret"
    )

    @Test
    fun embyStreamsThroughApiRoot() {
        val playback = emby.playbackSession(
            embySession,
            request,
            info(
                """{"Id": "ms1", "SupportsDirectPlay": true,
                   "MediaStreams": [{"Type": "Subtitle", "Language": "eng", "DeliveryMethod": "External",
                                     "DeliveryUrl": "/Videos/item1/ms1/Subtitles/3/Stream.srt"}]}"""
            ),
            deviceId = "d1"
        )
        assertTrue(playback.url.startsWith("https://media.example.com/emby/Videos/item1/stream?static=true&mediaSourceId=ms1&playSessionId=ps1"))
        assertTrue(playback.url.endsWith("&api_key=secret"))
        assertEquals("https://media.example.com/emby/Videos/item1/ms1/Subtitles/3/Stream.srt?api_key=secret", playback.subtitles.single().url)
    }

    @Test
    fun embyResolvesRelativeTranscodeUrlsOnce() {
        val transcode = request.copy(capabilities = ServerPlayerCapabilities(allowDirectPlay = false))
        val relative = emby.playbackSession(
            embySession,
            transcode,
            info("""{"Id": "ms1", "TranscodingUrl": "/videos/item1/master.m3u8?MediaSourceId=ms1&api_key=secret"}"""),
            deviceId = "d1"
        )
        assertEquals("https://media.example.com/emby/videos/item1/master.m3u8?MediaSourceId=ms1&api_key=secret", relative.url)
        val prefixed = emby.playbackSession(
            embySession,
            transcode,
            info("""{"Id": "ms1", "TranscodingUrl": "/emby/videos/item1/master.m3u8?MediaSourceId=ms1"}"""),
            deviceId = "d1"
        )
        assertEquals("https://media.example.com/emby/videos/item1/master.m3u8?MediaSourceId=ms1&api_key=secret", prefixed.url)
    }

    @Test
    fun listsSourceAudioAndMarksTheOneInTheStream() {
        val streams = """[{"Type": "Video", "Index": 0},
            {"Type": "Audio", "Index": 1, "Language": "eng", "DisplayTitle": "English - AC3 - 5.1 - Default"},
            {"Type": "Audio", "Index": 2, "Language": "jpn", "DisplayTitle": "Japanese - AAC - Stereo"}]"""
        val transcode = request.copy(capabilities = ServerPlayerCapabilities(allowDirectPlay = false))
        val chosen = jellyfin.playbackSession(
            session,
            transcode,
            info("""{"Id": "ms1", "DefaultAudioStreamIndex": 1, "MediaStreams": $streams,
                     "TranscodingUrl": "/videos/item1/master.m3u8?MediaSourceId=ms1&AudioStreamIndex=2"}"""),
            deviceId = "d1"
        )
        assertEquals(
            listOf(Triple(1, "eng", false), Triple(2, "jpn", true)),
            chosen.audioTracks.map { Triple(it.index, it.language, it.selected) }
        )
        assertEquals("Japanese - AAC - Stereo", chosen.audioTracks.last().label)
        val default = jellyfin.playbackSession(
            session,
            transcode,
            info("""{"Id": "ms1", "DefaultAudioStreamIndex": 1, "MediaStreams": $streams, "TranscodingUrl": "/videos/item1/master.m3u8"}"""),
            deviceId = "d1"
        )
        assertEquals(1, default.audioTracks.single { it.selected }.index)
    }

    @Test
    fun listsBurnInSubtitlesAndTranscodeReasons() {
        val playback = jellyfin.playbackSession(
            session,
            request.copy(capabilities = ServerPlayerCapabilities(allowDirectPlay = false)),
            info(
                """{"Id": "ms1",
                   "MediaStreams": [
                     {"Type": "Subtitle", "Index": 3, "Codec": "PGSSUB", "Language": "eng", "DisplayTitle": "English - PGSSUB", "DeliveryMethod": "Encode"},
                     {"Type": "Subtitle", "Index": 4, "Codec": "subrip", "Language": "spa", "DeliveryMethod": "External",
                      "DeliveryUrl": "/Videos/item1/ms1/Subtitles/4/0/Stream.srt"}],
                   "TranscodingUrl": "/videos/item1/master.m3u8?SubtitleStreamIndex=3&SubtitleMethod=Encode&TranscodeReasons=AudioCodecNotSupported%2C%20SubtitleCodecNotSupported"}"""
            ),
            deviceId = "d1"
        )
        assertEquals(listOf(Triple(3, "English - PGSSUB", true)), playback.burnInSubtitles.map { Triple(it.index, it.label, it.selected) })
        assertEquals(listOf("spa"), playback.subtitles.map { it.lang })
        assertEquals(listOf("AudioCodecNotSupported", "SubtitleCodecNotSupported"), playback.transcodeReasons)
        assertEquals("Audio codec not supported", readableTranscodeReason(playback.transcodeReasons.first()))
    }

    @Test
    fun leavesEmbeddedSubtitlesToThePlayer() {
        val playback = jellyfin.playbackSession(
            session,
            request,
            info(
                """{"Id": "ms1", "SupportsDirectPlay": true,
                   "MediaStreams": [
                     {"Type": "Subtitle", "Index": 2, "Codec": "subrip", "Language": "eng", "DeliveryMethod": "Embed"},
                     {"Type": "Subtitle", "Index": 3, "Codec": "subrip", "Language": "spa", "IsExternal": true,
                      "DeliveryMethod": "External", "DeliveryUrl": "/Videos/item1/ms1/Subtitles/3/0/Stream.srt"}]}"""
            ),
            deviceId = "d1"
        )
        assertEquals(listOf("spa"), playback.subtitles.map { it.lang })
    }
}
