package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.TorboxApi
import com.nuvio.tv.data.remote.dto.TorboxCreateTorrentDataDto
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TorboxResolver"

@Singleton
class TorboxDirectDebridResolver @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val api: TorboxApi,
    private val fileSelector: TorboxFileSelector
) {
    suspend fun resolve(
        stream: Stream,
        season: Int?,
        episode: Int?
    ): DirectDebridResolveResult {
        val resolve = stream.clientResolve ?: return DirectDebridResolveResult.Error
        val apiKey = dataStore.settings.first().torboxApiKey.trim()
        if (apiKey.isBlank()) return DirectDebridResolveResult.MissingApiKey
        val magnet = resolve.magnetUri?.takeIf { it.isNotBlank() }
            ?: buildMagnetUri(resolve)
            ?: return DirectDebridResolveResult.Stale
        val authorization = "Bearer $apiKey"

        return try {
            Log.d(TAG, "resolve: createTorrent hash=${resolve.infoHash?.take(12)}...")
            val createStartMs = System.currentTimeMillis()
            val create = api.createTorrent(
                authorization = authorization,
                magnet = magnet.toTextPart(),
                addOnlyIfCached = "true".toTextPart(),
                allowZip = "false".toTextPart()
            )
            Log.d(TAG, "resolve: createTorrent done in ${System.currentTimeMillis() - createStartMs}ms code=${create.code()}")
            val torrentId = create.extractTorrentId() ?: return create.toFailureForCreate()

            Log.d(TAG, "resolve: getTorrent id=$torrentId")
            val getTorrentStartMs = System.currentTimeMillis()
            val torrent = api.getTorrent(
                authorization = authorization,
                id = torrentId,
                bypassCache = true
            )
            Log.d(TAG, "resolve: getTorrent done in ${System.currentTimeMillis() - getTorrentStartMs}ms code=${torrent.code()}")
            if (!torrent.isSuccessful) return DirectDebridResolveResult.Stale
            val files = torrent.body()?.data?.files.orEmpty()
            val file = fileSelector.selectFile(files, resolve, season, episode)
                ?: return DirectDebridResolveResult.Stale
            val fileId = file.id ?: return DirectDebridResolveResult.Stale

            Log.d(TAG, "resolve: requestDownloadLink torrentId=$torrentId fileId=$fileId")
            val linkStartMs = System.currentTimeMillis()
            val link = api.requestDownloadLink(
                authorization = authorization,
                token = apiKey,
                torrentId = torrentId,
                fileId = fileId,
                zipLink = false,
                redirect = false,
                appendName = false
            )
            Log.d(TAG, "resolve: requestDownloadLink done in ${System.currentTimeMillis() - linkStartMs}ms code=${link.code()}")
            if (!link.isSuccessful) return DirectDebridResolveResult.Stale
            val url = link.body()?.data?.takeIf { it.isNotBlank() }
                ?: return DirectDebridResolveResult.Stale

            DirectDebridResolveResult.Success(
                url = url,
                filename = file.displayName().takeIf { it.isNotBlank() },
                videoSize = file.size
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "resolve: failed with ${error::class.simpleName}: ${error.message}")
            DirectDebridResolveResult.Error
        }
    }

    private fun Response<com.nuvio.tv.data.remote.dto.TorboxEnvelopeDto<TorboxCreateTorrentDataDto>>.extractTorrentId(): Int? {
        if (!isSuccessful) return null
        val body = body()
        if (body?.success == false) return null
        return body?.data?.resolvedTorrentId()
    }

    private fun Response<com.nuvio.tv.data.remote.dto.TorboxEnvelopeDto<TorboxCreateTorrentDataDto>>.toFailureForCreate(): DirectDebridResolveResult {
        return when (code()) {
            401, 403 -> DirectDebridResolveResult.Error
            409 -> DirectDebridResolveResult.NotCached
            else -> DirectDebridResolveResult.Stale
        }
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

    private fun String.toTextPart(): RequestBody {
        return toRequestBody("text/plain".toMediaType())
    }
}

sealed class DirectDebridResolveResult {
    data class Success(
        val url: String,
        val filename: String?,
        val videoSize: Long?
    ) : DirectDebridResolveResult()

    data object MissingApiKey : DirectDebridResolveResult()
    data object NotCached : DirectDebridResolveResult()
    data object Stale : DirectDebridResolveResult()
    data object Error : DirectDebridResolveResult()
}
