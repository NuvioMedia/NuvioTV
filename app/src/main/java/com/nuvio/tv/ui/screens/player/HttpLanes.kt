package com.nuvio.tv.ui.screens.player

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP/2 multiplexes every request to a host onto one connection per pool, so parallel chunk
 * requests end up sharing a single TCP receive window. Giving each lane its own pool keeps one
 * connection per lane, and requests are handed out in turn.
 */
internal class LaneCallFactory(val lanes: List<OkHttpClient>) : Call.Factory {
    private val next = AtomicInteger()

    override fun newCall(request: Request): Call =
        lanes[Math.floorMod(next.getAndIncrement(), lanes.size)].newCall(request)

    /** Starts the next session on lane 0, the lane that holds the connection warmed before playback. */
    fun restart() = next.set(0)
}

internal object HttpLanes {
    const val MAX_LANES = 4

    /**
     * [client] itself when HTTP/2 is off or only one connection is wanted. Otherwise one lane per
     * connection; lane 0 keeps [client]'s pool so a connection warmed before playback is reused.
     */
    fun callFactory(
        client: OkHttpClient,
        connections: Int,
        lanePool: (Int) -> ConnectionPool
    ): Call.Factory {
        val http2 = client.protocols.any { it == Protocol.HTTP_2 || it == Protocol.H2_PRIOR_KNOWLEDGE }
        if (!http2 || connections <= 1) return client
        val count = connections.coerceAtMost(MAX_LANES)
        return LaneCallFactory(
            List(count) { index ->
                if (index == 0) client else client.newBuilder().connectionPool(lanePool(index)).build()
            }
        )
    }
}
