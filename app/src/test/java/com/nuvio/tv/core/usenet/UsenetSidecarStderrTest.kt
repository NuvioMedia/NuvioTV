package com.nuvio.tv.core.usenet

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import org.junit.Assert.assertTrue
import org.junit.Test

class UsenetSidecarStderrTest {
    @Test fun `stderr closed under the read while the engine stops is not an error`() {
        var closed = false
        val stopped = object : InputStream() {
            override fun read(): Int = throw InterruptedIOException("read interrupted by close() on another thread")
            override fun close() { closed = true }
        }
        UsenetSidecar.drainQuietly(stopped)
        assertTrue(closed)
    }

    @Test fun `stderr is read to its end`() {
        val input = ByteArrayInputStream(ByteArray(10_000))
        UsenetSidecar.drainQuietly(input)
        assertTrue(input.available() == 0)
    }
}
