package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.RealDebridAddTorrentDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentFileDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import com.nuvio.tv.data.remote.dto.RealDebridUnrestrictLinkDto
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class RealDebridDirectDebridResolverTest {
    @Test
    fun `resolve adds magnet selects file and unrestricts generated link`() = runTest {
        val api = FakeRealDebridApi(
            addResponse = Response.success(201, RealDebridAddTorrentDto(id = "rd_torrent")),
            infoResponses = mutableListOf(
                Response.success(
                    RealDebridTorrentInfoDto(
                        id = "rd_torrent",
                        status = "waiting_files_selection",
                        files = listOf(
                            RealDebridTorrentFileDto(id = 1, path = "/wrong.mkv", bytes = 10),
                            RealDebridTorrentFileDto(id = 7, path = "/right.mkv", bytes = 20)
                        )
                    )
                ),
                Response.success(
                    RealDebridTorrentInfoDto(
                        id = "rd_torrent",
                        status = "downloaded",
                        files = listOf(RealDebridTorrentFileDto(id = 7, path = "/right.mkv", bytes = 20, selected = 1)),
                        links = listOf("https://real-debrid.example/link")
                    )
                )
            ),
            unrestrictResponse = Response.success(
                RealDebridUnrestrictLinkDto(
                    filename = "right.mkv",
                    filesize = 20,
                    download = "https://cdn.example/right.mkv"
                )
            )
        )
        val resolver = resolver(api)

        val result = resolver.resolve(stream(fileIdx = 7), season = null, episode = null)

        assertTrue(result is DirectDebridResolveResult.Success)
        result as DirectDebridResolveResult.Success
        assertEquals("https://cdn.example/right.mkv", result.url)
        assertEquals("right.mkv", result.filename)
        assertEquals(20L, result.videoSize)
        assertEquals(1, api.addCalls)
        assertEquals(2, api.infoCalls)
        assertEquals(1, api.selectCalls)
        assertEquals("7", api.lastSelectedFiles)
        assertEquals(1, api.unrestrictCalls)
        assertEquals(0, api.deleteCalls)
    }

    @Test
    fun `resolve deletes transient torrent when cached listing is stale`() = runTest {
        val api = FakeRealDebridApi(
            addResponse = Response.success(201, RealDebridAddTorrentDto(id = "rd_torrent")),
            infoResponses = mutableListOf(
                Response.success(
                    RealDebridTorrentInfoDto(
                        id = "rd_torrent",
                        status = "waiting_files_selection",
                        files = listOf(RealDebridTorrentFileDto(id = 7, path = "/right.mkv", bytes = 20))
                    )
                ),
                Response.success(
                    RealDebridTorrentInfoDto(
                        id = "rd_torrent",
                        status = "downloading",
                        files = listOf(RealDebridTorrentFileDto(id = 7, path = "/right.mkv", bytes = 20, selected = 1)),
                        links = emptyList()
                    )
                )
            )
        )
        val resolver = resolver(api)

        val result = resolver.resolve(stream(fileIdx = 7), season = null, episode = null)

        assertTrue(result is DirectDebridResolveResult.Stale)
        assertEquals(1, api.deleteCalls)
        assertEquals(0, api.unrestrictCalls)
    }

    private fun resolver(api: RealDebridApi): RealDebridDirectDebridResolver {
        val dataStore = mockk<DebridSettingsDataStore>()
        every { dataStore.settings } returns flowOf(
            DebridSettings(
                enabled = true,
                realDebridApiKey = "rd_token"
            )
        )
        return RealDebridDirectDebridResolver(
            dataStore = dataStore,
            api = api,
            fileSelector = RealDebridFileSelector()
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
        addonName = DebridProviders.instantName(DebridProviders.REAL_DEBRID_ID),
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
            service = "realdebrid",
            serviceIndex = 0,
            serviceExtension = null,
            isCached = true
        )
    )

    private class FakeRealDebridApi(
        private val addResponse: Response<RealDebridAddTorrentDto>,
        private val infoResponses: MutableList<Response<RealDebridTorrentInfoDto>>,
        private val unrestrictResponse: Response<RealDebridUnrestrictLinkDto> = Response.success(
            RealDebridUnrestrictLinkDto(download = "")
        )
    ) : RealDebridApi {
        var addCalls = 0
        var infoCalls = 0
        var selectCalls = 0
        var unrestrictCalls = 0
        var deleteCalls = 0
        var lastSelectedFiles: String? = null

        override suspend fun getUser(authorization: String): Response<ResponseBody> {
            return Response.success("{}".toResponseBody())
        }

        override suspend fun addMagnet(
            authorization: String,
            magnet: String
        ): Response<RealDebridAddTorrentDto> {
            addCalls++
            return addResponse
        }

        override suspend fun getTorrentInfo(
            authorization: String,
            id: String
        ): Response<RealDebridTorrentInfoDto> {
            infoCalls++
            return infoResponses.removeAt(0)
        }

        override suspend fun selectFiles(
            authorization: String,
            id: String,
            files: String
        ): Response<ResponseBody> {
            selectCalls++
            lastSelectedFiles = files
            return Response.success("".toResponseBody())
        }

        override suspend fun unrestrictLink(
            authorization: String,
            link: String
        ): Response<RealDebridUnrestrictLinkDto> {
            unrestrictCalls++
            return unrestrictResponse
        }

        override suspend fun deleteTorrent(
            authorization: String,
            id: String
        ): Response<ResponseBody> {
            deleteCalls++
            return Response.success("".toResponseBody())
        }
    }
}
