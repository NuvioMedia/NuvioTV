package com.nuvio.tv.core.connection

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicLong

/**
 * Player-facing side of connection-speed learning. The player attaches [networkByteCounter]
 * (directly or through [countingNetworkBytes]) to its HTTP data source factories and [onExoTick] / [onMpvTick] from its progress loop; a
 * sampler is kept per stream URL and reports once, when enough data has been observed or the
 * stream changes or the player is released.
 */
internal object PlaybackThroughput {
    private val networkBytes = AtomicLong()
    private var sampler: PlaybackThroughputSampler? = null
    private var samplerUrl: String? = null

    /** Counts bytes a data source receives over the network; attach with setTransferListener. */
    @OptIn(UnstableApi::class)
    val networkByteCounter: TransferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            bytesTransferred: Int,
        ) {
            if (isNetwork) networkBytes.addAndGet(bytesTransferred.toLong())
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    /** Counts bytes received over the network by every data source the factory creates. */
    @OptIn(UnstableApi::class)
    fun countingNetworkBytes(factory: DataSource.Factory): DataSource.Factory =
        DataSource.Factory { factory.createDataSource().apply { addTransferListener(networkByteCounter) } }

    /** ExoPlayer tick: bytes counted since the last tick, gated on whether the player is loading. */
    fun onExoTick(context: Context, streamUrl: String?, isLoading: Boolean) {
        samplerFor(context, streamUrl ?: return).onBytesTick(networkBytes.getAndSet(0L), isLoading)
    }

    /** mpv tick: its own download rate (`cache-speed`), gated on the demuxer still reading. */
    fun onMpvTick(context: Context, streamUrl: String?, bytesPerSecond: Long, isFetching: Boolean) {
        samplerFor(context, streamUrl ?: return).onRateTick(bytesPerSecond, isFetching)
    }

    fun finish() {
        sampler?.finish()
        sampler = null
        samplerUrl = null
    }

    private fun samplerFor(context: Context, streamUrl: String): PlaybackThroughputSampler {
        sampler?.takeIf { samplerUrl == streamUrl }?.let { return it }
        finish()
        networkBytes.set(0L)
        val appContext = context.applicationContext
        ConnectionSpeedEstimator.ensureLoaded(appContext) // starts network tracking before the first tick
        return PlaybackThroughputSampler(streamUrl) { network, mbps ->
            ConnectionSpeedEstimator.record(appContext, network, mbps)
        }.also {
            sampler = it
            samplerUrl = streamUrl
        }
    }
}
