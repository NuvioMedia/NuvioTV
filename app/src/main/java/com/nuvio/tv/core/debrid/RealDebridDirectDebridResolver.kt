package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.RealDebridApi
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealDebridDirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val api: RealDebridApi,
    private val fileSelector: RealDebridFileSelector
) {
    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val apiKey = dataStore.settings.first().realDebridApiKey.trim()
        if (apiKey.isBlank()) return DirectDebridResolveResult.MissingApiKey
        val magnet = resolve.magnetUri?.takeIf { it.isNotBlank() }
            ?: buildMagnetUri(resolve)
            ?: return DirectDebridResolveResult.Stale
        val authorization = "Bearer $apiKey"

        return try {
            val add = api.addMagnet(authorization, magnet)
            val torrentId = add.body()?.id?.takeIf { add.isSuccessful && it.isNotBlank() }
                ?: return add.toFailureForAdd()
            var resolved = false
            try {
                val infoBefore = api.getTorrentInfo(authorization, torrentId)
                if (!infoBefore.isSuccessful) return DirectDebridResolveResult.Stale
                val file = fileSelector.selectFile(
                    files = infoBefore.body()?.files.orEmpty(),
                    resolve = resolve,
                    season = season,
                    episode = episode
                ) ?: return DirectDebridResolveResult.Stale
                val fileId = file.id ?: return DirectDebridResolveResult.Stale
                val select = api.selectFiles(
                    authorization = authorization,
                    id = torrentId,
                    files = fileId.toString()
                )
                if (!select.isSuccessful && select.code() != 202) {
                    return DirectDebridResolveResult.Stale
                }

                val infoAfter = api.getTorrentInfo(authorization, torrentId)
                if (!infoAfter.isSuccessful) return DirectDebridResolveResult.Stale
                val link = infoAfter.body()?.firstDownloadLink()
                    ?: return DirectDebridResolveResult.Stale
                val unrestrict = api.unrestrictLink(authorization, link)
                if (!unrestrict.isSuccessful) return DirectDebridResolveResult.Stale
                val url = unrestrict.body()?.download?.takeIf { it.isNotBlank() }
                    ?: return DirectDebridResolveResult.Stale
                resolved = true
                DirectDebridResolveResult.Success(
                    url = url,
                    filename = unrestrict.body()?.filename?.takeIf { it.isNotBlank() }
                        ?: file.displayName().takeIf { it.isNotBlank() },
                    videoSize = unrestrict.body()?.filesize ?: file.bytes
                )
            } finally {
                if (!resolved) {
                    runCatching { api.deleteTorrent(authorization, torrentId) }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            DirectDebridResolveResult.Error
        }
    }

    private fun retrofit2.Response<com.nuvio.tv.data.remote.dto.RealDebridAddTorrentDto>.toFailureForAdd(): DirectDebridResolveResult {
        return when (code()) {
            401, 403 -> DirectDebridResolveResult.Error
            else -> DirectDebridResolveResult.Stale
        }
    }

    private fun RealDebridTorrentInfoDto.firstDownloadLink(): String? {
        if (!status.equals("downloaded", ignoreCase = true)) return null
        return links.orEmpty().firstOrNull { it.isNotBlank() }
    }

    private fun buildMagnetUri(resolve: StreamClientResolve): String? {
        val hash = resolve.infoHash?.takeIf { it.isNotBlank() } ?: return null
        return buildString {
            append("magnet:?xt=urn:btih:")
            append(hash)
            resolve.sources
                ?.filter { it.isNotBlank() }
                ?.forEach { source ->
                    append("&tr=")
                    append(java.net.URLEncoder.encode(source, "UTF-8"))
                }
        }
    }
}
