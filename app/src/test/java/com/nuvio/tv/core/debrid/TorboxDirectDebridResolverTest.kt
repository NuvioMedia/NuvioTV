package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.data.remote.dto.TorboxCloudItemDto
import com.nuvio.tv.data.remote.dto.TorboxCachedItemDto
import com.nuvio.tv.data.remote.dto.TorboxCheckCachedRequestDto
import com.nuvio.tv.data.remote.dto.TorboxCreateTorrentDataDto
import com.nuvio.tv.data.remote.dto.TorboxDeviceAuthorizationDto
import com.nuvio.tv.data.remote.dto.TorboxDeviceTokenDto
import com.nuvio.tv.data.remote.dto.TorboxDeviceTokenRequestDto
import com.nuvio.tv.data.remote.dto.TorboxEnvelopeDto
import com.nuvio.tv.data.remote.dto.TorboxTorrentDataDto
import com.nuvio.tv.data.remote.dto.TorboxTorrentFileDto
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TorboxDirectDebridResolverTest {
    @Test
    fun `resolve creates cached torrent then requests selected file link`() = runTest {
        val api = FakeTorboxApi(
            createResponse = Response.success(
                TorboxEnvelopeDto(
                    success = true,
                    data = TorboxCreateTorrentDataDto(torrentId = 44)
                )
            ),
            torrentResponse = Response.success(
                TorboxEnvelopeDto(
                    success = true,
                    data = TorboxTorrentDataDto(
                        id = 44,
                        files = listOf(
                            TorboxTorrentFileDto(id = 1, name = "wrong.mkv", size = 10),
                            TorboxTorrentFileDto(id = 7, name = "right.mkv", size = 20)
                        )
                    )
                )
            ),
            linkResponse = Response.success(TorboxEnvelopeDto(success = true, data = "https://cdn.example/right.mkv"))
        )
        val resolver = resolver(api)

        val result = resolver.resolve(stream(fileIdx = 7), season = null, episode = null)

        assertTrue(result is DirectDebridResolveResult.Success)
        result as DirectDebridResolveResult.Success
        assertEquals("https://cdn.example/right.mkv", result.url)
        assertEquals("right.mkv", result.filename)
        assertEquals(20L, result.videoSize)
        assertEquals(1, api.createCalls)
        assertEquals(1, api.torrentCalls)
        assertEquals(1, api.requestCalls)
        assertEquals(true, api.lastAddOnlyIfCached)
        assertEquals(false, api.lastAllowZip)
        assertEquals(7, api.lastFileId)
    }

    @Test
    fun `resolve treats non cached create response as stale`() = runTest {
        val api = FakeTorboxApi(
            createResponse = Response.error(
                400,
                """{"success":false}""".toResponseBody("application/json".toMediaType())
            )
        )
        val resolver = resolver(api)

        val result = resolver.resolve(stream(fileIdx = 1), season = null, episode = null)

        assertTrue(result is DirectDebridResolveResult.Stale)
        assertEquals(1, api.createCalls)
        assertEquals(0, api.torrentCalls)
        assertEquals(0, api.requestCalls)
    }

    @Test
    fun `torbox api exposes checkcached endpoint for local torrent availability`() {
        val hasCheckCached = TorboxApi::class.java.methods.any {
            it.name.contains("checkcached", ignoreCase = true) ||
                it.name.contains("checkCached", ignoreCase = true)
        }

        assertTrue(hasCheckCached)
    }

    private fun resolver(api: TorboxApi): TorboxDirectDebridResolver {
        val dataStore = mockk<DebridSettingsDataStore>()
        every { dataStore.settings } returns flowOf(
            DebridSettings(
                enabled = true,
                torboxApiKey = "tb_token"
            )
        )
        return TorboxDirectDebridResolver(
            dataStore = dataStore,
            api = api,
            fileSelector = TorboxFileSelector()
        )
    }

    private fun stream(fileIdx: Int?): Stream = Stream(
        name = "Direct Debrid",
        title = "Title",
        description = "Description",
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = DebridProviders.instantName(DebridProviders.TORBOX_ID),
        addonLogo = null,
        clientResolve = StreamClientResolve(
            type = "debrid",
            infoHash = "abcdef",
            fileIdx = fileIdx,
            magnetUri = "magnet:?xt=urn:btih:abcdef",
            sources = null,
            torrentName = "Torrent",
            filename = "right.mkv",
            mediaType = "movie",
            mediaId = "tt1",
            mediaOnlyId = "tt1",
            title = "Title",
            season = null,
            episode = null,
            service = "torbox",
            serviceIndex = 0,
            serviceExtension = null,
            isCached = true
        )
    )

    private class FakeTorboxApi(
        private val createResponse: Response<TorboxEnvelopeDto<TorboxCreateTorrentDataDto>>,
        private val torrentResponse: Response<TorboxEnvelopeDto<TorboxTorrentDataDto>> = Response.success(
            TorboxEnvelopeDto(success = true, data = TorboxTorrentDataDto(files = emptyList()))
        ),
        private val linkResponse: Response<TorboxEnvelopeDto<String>> = Response.success(
            TorboxEnvelopeDto(success = true, data = "")
        )
    ) : TorboxApi {
        var createCalls = 0
        var torrentCalls = 0
        var requestCalls = 0
        var lastAddOnlyIfCached: Boolean? = null
        var lastAllowZip: Boolean? = null
        var lastFileId: Int? = null

        override suspend fun getUser(authorization: String): Response<ResponseBody> {
            return Response.success("{}".toResponseBody("application/json".toMediaType()))
        }

        override suspend fun startDeviceAuthorization(
            appName: String
        ): Response<TorboxEnvelopeDto<TorboxDeviceAuthorizationDto>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = TorboxDeviceAuthorizationDto()))
        }

        override suspend fun redeemDeviceAuthorization(
            body: TorboxDeviceTokenRequestDto
        ): Response<TorboxEnvelopeDto<TorboxDeviceTokenDto>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = TorboxDeviceTokenDto()))
        }

        override suspend fun checkCached(
            authorization: String,
            format: String,
            body: TorboxCheckCachedRequestDto
        ): Response<TorboxEnvelopeDto<Map<String, TorboxCachedItemDto>>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = emptyMap()))
        }

        override suspend fun createTorrent(
            authorization: String,
            magnet: RequestBody,
            addOnlyIfCached: RequestBody,
            allowZip: RequestBody
        ): Response<TorboxEnvelopeDto<TorboxCreateTorrentDataDto>> {
            createCalls++
            lastAddOnlyIfCached = addOnlyIfCached.readUtf8().toBooleanStrictOrNull()
            lastAllowZip = allowZip.readUtf8().toBooleanStrictOrNull()
            return createResponse
        }

        override suspend fun getTorrent(
            authorization: String,
            id: Int,
            bypassCache: Boolean
        ): Response<TorboxEnvelopeDto<TorboxTorrentDataDto>> {
            torrentCalls++
            return torrentResponse
        }

        override suspend fun requestDownloadLink(
            authorization: String,
            token: String,
            torrentId: Int,
            fileId: Int?,
            zipLink: Boolean,
            redirect: Boolean,
            appendName: Boolean
        ): Response<TorboxEnvelopeDto<String>> {
            requestCalls++
            lastFileId = fileId
            return linkResponse
        }

        override suspend fun listCloudTorrents(
            authorization: String
        ): Response<TorboxEnvelopeDto<List<TorboxCloudItemDto>>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = emptyList()))
        }

        override suspend fun listCloudUsenet(
            authorization: String
        ): Response<TorboxEnvelopeDto<List<TorboxCloudItemDto>>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = emptyList()))
        }

        override suspend fun listCloudWebDownloads(
            authorization: String
        ): Response<TorboxEnvelopeDto<List<TorboxCloudItemDto>>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = emptyList()))
        }

        override suspend fun requestCloudTorrentDownloadLink(
            authorization: String,
            token: String,
            torrentId: String,
            fileId: String?,
            zipLink: Boolean,
            redirect: Boolean,
            appendName: Boolean
        ): Response<TorboxEnvelopeDto<String>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = ""))
        }

        override suspend fun requestCloudUsenetDownloadLink(
            authorization: String,
            token: String,
            usenetId: String,
            fileId: String?,
            zipLink: Boolean,
            redirect: Boolean,
            appendName: Boolean
        ): Response<TorboxEnvelopeDto<String>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = ""))
        }

        override suspend fun requestCloudWebDownloadLink(
            authorization: String,
            token: String,
            webId: String,
            fileId: String?,
            zipLink: Boolean,
            redirect: Boolean,
            appendName: Boolean
        ): Response<TorboxEnvelopeDto<String>> {
            return Response.success(TorboxEnvelopeDto(success = true, data = ""))
        }

        private fun RequestBody.readUtf8(): String {
            val buffer = okio.Buffer()
            writeTo(buffer)
            return buffer.readUtf8()
        }
    }
}
