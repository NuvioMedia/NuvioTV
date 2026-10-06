package com.nuvio.tv.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class ConnectionSpeedTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 100L * day

    @Test
    fun `estimate needs two recent samples on the current network`() {
        val samples = listOf(sample(NetworkKind.WIFI, 80.0), sample(NetworkKind.CELLULAR, 20.0))

        assertNull(ConnectionSpeedEstimator.estimateMbps(samples, NetworkKind.WIFI, now))
        assertNull(ConnectionSpeedEstimator.estimateMbps(samples, NetworkKind.CELLULAR, now))
    }

    @Test
    fun `estimate uses the fastest recent sample so one slow host does not dominate`() {
        val samples = listOf(
            sample(NetworkKind.WIFI, 6.0),
            sample(NetworkKind.WIFI, 95.0),
            sample(NetworkKind.WIFI, 4.0),
            sample(NetworkKind.CELLULAR, 300.0),
        )

        assertEquals(95.0, ConnectionSpeedEstimator.estimateMbps(samples, NetworkKind.WIFI, now))
    }

    @Test
    fun `estimate ignores stale samples`() {
        val samples = listOf(
            sample(NetworkKind.WIFI, 200.0, ageMs = 15 * day),
            sample(NetworkKind.WIFI, 40.0),
            sample(NetworkKind.WIFI, 30.0),
        )

        assertEquals(40.0, ConnectionSpeedEstimator.estimateMbps(samples, NetworkKind.WIFI, now))
    }

    @Test
    fun `estimate follows a connection that got slower within three playbacks`() {
        val samples = listOf(
            sample(NetworkKind.WIFI, 200.0),
            sample(NetworkKind.WIFI, 20.0),
            sample(NetworkKind.WIFI, 18.0),
            sample(NetworkKind.WIFI, 22.0),
        )

        assertEquals(22.0, ConnectionSpeedEstimator.estimateMbps(samples, NetworkKind.WIFI, now))
    }

    @Test
    fun `estimate skips invalid stored samples`() {
        val samples = listOf(
            sample(NetworkKind.WIFI, Double.NaN),
            sample(NetworkKind.WIFI, Double.POSITIVE_INFINITY),
            sample(NetworkKind.WIFI, 30.0),
        )

        assertNull(ConnectionSpeedEstimator.estimateMbps(samples, NetworkKind.WIFI, now))
    }

    @Test
    fun `append keeps a bounded history per network`() {
        var samples = listOf(sample(NetworkKind.CELLULAR, 10.0))
        repeat(10) { index -> samples = ConnectionSpeedEstimator.appendSample(samples, sample(NetworkKind.WIFI, index.toDouble())) }

        val wifi = samples.filter { it.network == NetworkKind.WIFI }.map { it.mbps }
        assertEquals(listOf(7.0, 8.0, 9.0), wifi)
        assertEquals(1, samples.count { it.network == NetworkKind.CELLULAR })
    }

    @Test
    fun `sampler reports throughput only over time spent fetching`() {
        val clock = TestTimeSource()
        val reported = mutableListOf<Pair<NetworkKind, Double>>()
        val sampler = sampler(clock, reported)

        sampler.onBytesTick(0, isFetching = true)
        // Connection setup before the first byte is not counted.
        tick(clock, sampler, bytes = 0, isFetching = true)
        // Slow start: the first second of transfer is skipped.
        repeat(4) { tick(clock, sampler, bytes = 100_000, isFetching = true) }
        // 4 s at 5 MB/s (40 Mbps) while fetching.
        repeat(16) { tick(clock, sampler, bytes = 1_250_000, isFetching = true) }
        // Buffer full: the player stops downloading, which must not dilute the rate.
        repeat(40) { tick(clock, sampler, bytes = 0, isFetching = false) }
        sampler.finish()

        assertEquals(1, reported.size)
        assertEquals(40.0, reported.single().second, 0.01)
    }

    @Test
    fun `sampler ignores waits for connection setup and seeks mid-session`() {
        val clock = TestTimeSource()
        val reported = mutableListOf<Pair<NetworkKind, Double>>()
        val sampler = sampler(clock, reported)

        sampler.onBytesTick(0, isFetching = true)
        repeat(8) { tick(clock, sampler, bytes = 1_250_000, isFetching = true) }
        // Seek to the resume position: a new request is opened and nothing arrives for 3 s.
        repeat(12) { tick(clock, sampler, bytes = 0, isFetching = true) }
        repeat(8) { tick(clock, sampler, bytes = 1_250_000, isFetching = true) }
        sampler.finish()

        assertEquals(40.0, reported.single().second, 0.01)
    }

    @Test
    fun `sampler converts reported rates`() {
        val clock = TestTimeSource()
        val reported = mutableListOf<Pair<NetworkKind, Double>>()
        val sampler = sampler(clock, reported)

        sampler.onRateTick(0, isFetching = true)
        repeat(20) {
            clock += 250.milliseconds
            sampler.onRateTick(bytesPerSecond = 2_500_000, isFetching = true)
        }
        sampler.finish()

        assertEquals(20.0, reported.single().second, 0.01)
    }

    @Test
    fun `sampler discards sessions too short to be meaningful`() {
        val clock = TestTimeSource()
        val reported = mutableListOf<Pair<NetworkKind, Double>>()
        val sampler = sampler(clock, reported)

        sampler.onBytesTick(0, isFetching = true)
        repeat(8) { tick(clock, sampler, bytes = 1_000_000, isFetching = true) }
        sampler.finish()

        assertTrue(reported.isEmpty())
    }

    @Test
    fun `sampler accepts a long slow session with few bytes`() {
        val clock = TestTimeSource()
        val reported = mutableListOf<Pair<NetworkKind, Double>>()
        val sampler = sampler(clock, reported)

        sampler.onBytesTick(0, isFetching = true)
        // 12 s at 125 kB/s (1 Mbps).
        repeat(48) { tick(clock, sampler, bytes = 31_250, isFetching = true) }
        sampler.finish()

        assertEquals(1.0, reported.single().second, 0.01)
    }

    @Test
    fun `sampler ignores intervals where the app was suspended`() {
        val clock = TestTimeSource()
        val reported = mutableListOf<Pair<NetworkKind, Double>>()
        val sampler = sampler(clock, reported)

        sampler.onBytesTick(0, isFetching = true)
        repeat(16) { tick(clock, sampler, bytes = 1_250_000, isFetching = true) }
        clock += 60_000.milliseconds
        sampler.onBytesTick(1_000_000, isFetching = true)
        sampler.finish()

        assertEquals(40.0, reported.single().second, 0.01)
    }

    @Test
    fun `sampler reports once, after one second of warm-up and ten of measurement`() {
        val clock = TestTimeSource()
        val reported = mutableListOf<Pair<NetworkKind, Double>>()
        val sampler = sampler(clock, reported)

        sampler.onBytesTick(0, isFetching = true)
        repeat(43) { tick(clock, sampler, bytes = 1_250_000, isFetching = true) }
        assertTrue(reported.isEmpty())
        tick(clock, sampler, bytes = 1_250_000, isFetching = true)
        assertEquals(1, reported.size)

        repeat(200) { tick(clock, sampler, bytes = 1_250_000, isFetching = true) }
        sampler.finish()
        assertEquals(1, reported.size)
    }

    @Test
    fun `sampler credits the network it measured on`() {
        val clock = TestTimeSource()
        val reported = mutableListOf<Pair<NetworkKind, Double>>()
        var network: NetworkKind? = NetworkKind.CELLULAR
        val sampler = PlaybackThroughputSampler(
            sourceUrl = "https://cdn.example.com/movie.mkv",
            timeSource = clock,
            networkKind = { network },
            networkGeneration = { 1 },
            onSample = { kind, mbps -> reported += kind to mbps },
        )

        sampler.onBytesTick(0, isFetching = true)
        repeat(4) { tick(clock, sampler, bytes = 1_250_000, isFetching = true) }
        network = NetworkKind.WIFI
        repeat(16) { tick(clock, sampler, bytes = 1_250_000, isFetching = true) }
        sampler.finish()

        assertEquals(NetworkKind.CELLULAR, reported.single().first)
    }

    @Test
    fun `sampler drops a measurement that spans a network change`() {
        val clock = TestTimeSource()
        val reported = mutableListOf<Pair<NetworkKind, Double>>()
        var generation = 1
        val sampler = PlaybackThroughputSampler(
            sourceUrl = "https://cdn.example.com/movie.mkv",
            timeSource = clock,
            networkKind = { NetworkKind.WIFI },
            networkGeneration = { generation },
            onSample = { kind, mbps -> reported += kind to mbps },
        )

        sampler.onBytesTick(0, isFetching = true)
        repeat(20) { tick(clock, sampler, bytes = 1_250_000, isFetching = true) }
        generation = 2
        repeat(40) { tick(clock, sampler, bytes = 1_250_000, isFetching = true) }
        sampler.finish()

        assertTrue(reported.isEmpty())
    }

    @Test
    fun `sampler does not measure while offline`() {
        val clock = TestTimeSource()
        val reported = mutableListOf<Pair<NetworkKind, Double>>()
        val sampler = PlaybackThroughputSampler(
            sourceUrl = "https://cdn.example.com/movie.mkv",
            timeSource = clock,
            networkKind = { null },
            networkGeneration = { 1 },
            onSample = { kind, mbps -> reported += kind to mbps },
        )

        sampler.onBytesTick(0, isFetching = true)
        repeat(60) { tick(clock, sampler, bytes = 1_250_000, isFetching = true) }
        sampler.finish()

        assertTrue(reported.isEmpty())
    }

    @Test
    fun `sampler never reports for local or lan sources`() {
        listOf(
            "http://127.0.0.1:11470/stream/abc",
            "http://localhost:8080/video.mkv",
            "http://192.168.1.20:8096/Videos/1/stream",
            "file:///storage/emulated/0/Download/movie.mkv",
        ).forEach { url ->
            val clock = TestTimeSource()
            val reported = mutableListOf<Pair<NetworkKind, Double>>()
            val sampler = sampler(clock, reported, url = url)
            sampler.onBytesTick(0, isFetching = true)
            repeat(40) { tick(clock, sampler, bytes = 1_250_000, isFetching = true) }
            sampler.finish()
            assertTrue(url, reported.isEmpty())
        }
    }

    @Test
    fun `internet source detection`() {
        assertTrue("https://real-debrid.com/d/ABC/movie.mkv".isInternetPlaybackSource())
        assertTrue("http://user:pass@203.0.113.5:8080/video".isInternetPlaybackSource())
        assertTrue("https://[2001:db8::1]/video".isInternetPlaybackSource())
        assertTrue("https://172.32.0.1/video".isInternetPlaybackSource())
        assertFalse("http://10.0.0.2/video".isInternetPlaybackSource())
        assertFalse("http://172.20.1.1/video".isInternetPlaybackSource())
        assertFalse("http://[::1]:8080/video".isInternetPlaybackSource())
        assertFalse("http://nas.local/video".isInternetPlaybackSource())
        assertFalse("magnet:?xt=urn:btih:abc".isInternetPlaybackSource())
        assertFalse("content://media/external/video/1".isInternetPlaybackSource())
    }

    private fun tick(clock: TestTimeSource, sampler: PlaybackThroughputSampler, bytes: Long, isFetching: Boolean) {
        clock += 250.milliseconds
        sampler.onBytesTick(bytes, isFetching)
    }

    private fun sampler(
        clock: TestTimeSource,
        reported: MutableList<Pair<NetworkKind, Double>>,
        url: String = "https://cdn.example.com/movie.mkv",
    ) = PlaybackThroughputSampler(
        sourceUrl = url,
        timeSource = clock,
        networkKind = { NetworkKind.WIFI },
        networkGeneration = { 1 },
        onSample = { kind, mbps -> reported += kind to mbps },
    )

    private fun sample(network: NetworkKind, mbps: Double, ageMs: Long = 0L) =
        ConnectionSpeedSample(network = network, mbps = mbps, recordedAtMs = now - ageMs)
}
