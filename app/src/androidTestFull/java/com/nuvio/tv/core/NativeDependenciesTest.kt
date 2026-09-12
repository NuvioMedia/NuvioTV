package com.nuvio.tv.core

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.common.DataReader
import androidx.media3.datasource.DataSpec
import androidx.media3.decoder.iamf.IamfDecoder
import androidx.media3.decoder.iamf.IamfLibrary
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wireguard.android.backend.GoBackend
import org.conscrypt.Conscrypt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

/** Real native calls, isolated TLS loopback, and official PCM fixture; no VPN is started. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeDependenciesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun conscryptCompletesVerifiedTlsHandshakeOnLoopback() {
        assertTrue("Conscrypt native library must load", Conscrypt.isAvailable())
        val provider = Conscrypt.newProvider()
        val password = "test-fixture".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            instrumentation.context.assets.open("native/localhost-test.p12").use { load(it, password) }
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val serverContext = SSLContext.getInstance("TLS", provider).apply { init(keys.keyManagers, trust.trustManagers, SecureRandom()) }
        val clientContext = SSLContext.getInstance("TLS", provider).apply { init(null, trust.trustManagers, SecureRandom()) }
        val failure = AtomicReference<Throwable?>()
        (serverContext.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).use { server ->
            server.soTimeout = 5000
            val worker = thread(name = "conscrypt-loopback-test", isDaemon = true) {
                try {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 5000
                        socket.startHandshake()
                        assertEquals(42, socket.inputStream.read())
                        socket.outputStream.write(43)
                        socket.outputStream.flush()
                    }
                } catch (error: Throwable) { failure.set(error) }
            }
            try {
                (clientContext.socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket).use { socket ->
                    socket.soTimeout = 5000
                    socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    socket.startHandshake()
                    assertTrue(Conscrypt.isConscrypt(socket))
                    assertTrue(socket.session.protocol.startsWith("TLS"))
                    socket.outputStream.write(42)
                    socket.outputStream.flush()
                    assertEquals(43, socket.inputStream.read())
                }
            } finally {
                server.close()
                worker.join(6000)
            }
            assertFalse("Loopback TLS worker leaked", worker.isAlive)
            failure.get()?.let { throw AssertionError("Loopback TLS server failed", it) }
        }
    }

    @Test fun wireGuardLoadsNativeBackendWithoutCreatingTunnel() {
        val backend = GoBackend(instrumentation.targetContext)
        assertTrue("WireGuard JNI version must be available", backend.version.isNotBlank())
        assertTrue("A fresh test backend must not own a tunnel", backend.runningTunnelNames.isEmpty())
    }

    @Test fun iamfDecodesOfficialPcmFixtureWithExpectedStereoSamples() {
        assertTrue("IAMF JNI library must load", IamfLibrary.isAvailable())
        val bytes = instrumentation.context.assets.open("native/sample_iamf.mp4").use { it.readBytes() }
        val captured = CapturedAudio()
        val extractor = Mp4Extractor()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = captured
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        val source = ByteArrayDataSource(bytes)
        fun open(position: Long): DefaultExtractorInput {
            source.close()
            source.open(DataSpec.Builder().setUri(Uri.parse("memory:///sample_iamf.mp4")).setPosition(position).build())
            return DefaultExtractorInput(source, position, bytes.size.toLong())
        }
        var input = open(0)
        val seek = PositionHolder()
        try {
            var attempts = 0
            while (captured.sample == null && attempts++ < 1000) {
                when (extractor.read(input, seek)) {
                    Extractor.RESULT_SEEK -> input = open(seek.position)
                    Extractor.RESULT_END_OF_INPUT -> break
                }
            }
        } finally { source.close(); extractor.release() }
        val format = requireNotNull(captured.format)
        assertEquals(MimeTypes.AUDIO_IAMF, format.sampleMimeType)
        val sample = requireNotNull(captured.sample)
        val decoder = IamfDecoder(format.initializationData, false)
        try {
            assertEquals(2, decoder.channelCount)
            assertEquals(2, decoder.binauralLayoutChannelCount)
            val buffer = requireNotNull(decoder.dequeueInputBuffer())
            buffer.ensureSpaceForWrite(sample.size)
            buffer.data!!.put(sample)
            buffer.timeUs = captured.timeUs
            buffer.flip()
            decoder.queueInputBuffer(buffer)
            val deadline = SystemClock.elapsedRealtime() + 5000
            var output = decoder.dequeueOutputBuffer()
            while (output == null && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(5)
                output = decoder.dequeueOutputBuffer()
            }
            val decoded = requireNotNull(output) { "IAMF did not produce PCM" }
            try {
                val pcm = requireNotNull(decoded.data)
                assertTrue(pcm.hasRemaining())
                assertEquals(0, pcm.remaining() % 4)
                val channels = Array(2) { ByteArray(pcm.remaining() / 2) }
                var frame = 0
                while (pcm.hasRemaining()) {
                    pcm.get(channels[0], frame * 2, 2)
                    pcm.get(channels[1], frame * 2, 2)
                    frame++
                }
                // Media3 1.8.0 official CapturingAudioSink golden, buffer #0.
                assertEquals(1303596737, channels[0].contentHashCode())
                assertEquals(17085665, channels[1].contentHashCode())
            } finally { decoded.release() }
        } finally { decoder.release() }
    }

    private class CapturedAudio : TrackOutput {
        var format: Format? = null
        var sample: ByteArray? = null
        var timeUs = 0L
        private val pending = ByteArrayOutputStream()
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val bytes = ByteArray(length)
            val read = input.read(bytes, 0, length)
            if (read == -1) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            pending.write(bytes, 0, read)
            return read
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            pending.write(bytes)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val bytes = pending.toByteArray()
            if (sample == null) { sample = bytes.copyOfRange(bytes.size - offset - size, bytes.size - offset); this.timeUs = timeUs }
            pending.reset()
            if (offset > 0) pending.write(bytes, bytes.size - offset, offset)
        }
    }
}
