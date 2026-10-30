package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamSearchSessionCacheTest {

    @Test
    fun `full search leaves an in-flight plugin continuation running`() = runTest {
        val cache = StreamSearchSessionCache()
        val pluginCancelled = AtomicBoolean(false)
        val pluginStarted = CompletableDeferred<Unit>()
        val releasePlugin = CompletableDeferred<Unit>()
        val key = StreamSearchRequestKey(
            profileId = 1,
            type = "series",
            videoId = "tt1",
            season = 1,
            episode = 5,
            sourceConfiguration = "config",
            searchMode = "continuePluginOnly"
        )

        val pluginJob = launch {
            cache.observe(key, forceRefresh = false) {
                flow {
                    emit(NetworkResult.Loading)
                    pluginStarted.complete(Unit)
                    try {
                        releasePlugin.await()
                        emit(NetworkResult.Success(emptyList()))
                    } catch (error: CancellationException) {
                        pluginCancelled.set(true)
                        throw error
                    }
                }
            }.collect { }
        }
        pluginStarted.await()

        cache.observe(key.copy(searchMode = ""), forceRefresh = false) {
            flow { emit(NetworkResult.Success(emptyList())) }
        }.first { it is NetworkResult.Success }

        withContext(Dispatchers.IO) { delay(200) }
        assertFalse(pluginCancelled.get())
        releasePlugin.complete(Unit)
        pluginJob.cancel()
    }
}
