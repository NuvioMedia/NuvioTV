package androidx.media3.exoplayer.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class SampleDataQueueNativeTest {

    @Test
    fun testCopyFromArray() {
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val target = ByteBuffer.allocateDirect(20)

        // Success copy
        val success = SampleDataQueueNative.copyFromArray(source, 2, target, 5, 5)
        assertTrue("copyFromArray should succeed", success)

        // Verify content
        val verification = ByteArray(5)
        target.position(5)
        target.get(verification)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7), verification)

        // Boundary checks
        // 1. Negative offset
        assertFalse(SampleDataQueueNative.copyFromArray(source, -1, target, 0, 5))
        assertFalse(SampleDataQueueNative.copyFromArray(source, 0, target, -1, 5))
        // 2. Negative length
        assertFalse(SampleDataQueueNative.copyFromArray(source, 0, target, 0, -5))
        // 3. Length exceeds source
        assertFalse(SampleDataQueueNative.copyFromArray(source, 8, target, 0, 5))
        // 4. Length exceeds target capacity
        assertFalse(SampleDataQueueNative.copyFromArray(source, 0, target, 18, 5))
    }

    @Test
    fun testCopyToArray() {
        val source = ByteBuffer.allocateDirect(20)
        for (i in 0 until 10) {
            source.put(i, (i + 1).toByte())
        }
        val target = ByteArray(15)

        // Success copy
        val success = SampleDataQueueNative.copyToArray(source, 2, target, 4, 5)
        assertTrue("copyToArray should succeed", success)

        // Verify content
        val verification = ByteArray(5)
        System.arraycopy(target, 4, verification, 0, 5)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7), verification)

        // Boundary checks
        // 1. Negative offset
        assertFalse(SampleDataQueueNative.copyToArray(source, -1, target, 0, 5))
        assertFalse(SampleDataQueueNative.copyToArray(source, 0, target, -1, 5))
        // 2. Negative length
        assertFalse(SampleDataQueueNative.copyToArray(source, 0, target, 0, -5))
        // 3. Length exceeds source capacity
        assertFalse(SampleDataQueueNative.copyToArray(source, 18, target, 0, 5))
        // 4. Length exceeds target array size
        assertFalse(SampleDataQueueNative.copyToArray(source, 0, target, 12, 5))
    }

    @Test
    fun testCopyBetweenDirectBuffers() {
        val source = ByteBuffer.allocateDirect(20)
        val target = ByteBuffer.allocateDirect(20)
        for (i in 0 until 10) {
            source.put(i, (i + 1).toByte())
        }

        // Success copy
        val success = SampleDataQueueNative.copyBetweenDirectBuffers(source, 2, target, 5, 5)
        assertTrue("copyBetweenDirectBuffers should succeed", success)

        // Verify content
        val verification = ByteArray(5)
        target.position(5)
        target.get(verification)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7), verification)

        // Boundary checks
        // 1. Negative offset
        assertFalse(SampleDataQueueNative.copyBetweenDirectBuffers(source, -1, target, 0, 5))
        assertFalse(SampleDataQueueNative.copyBetweenDirectBuffers(source, 0, target, -1, 5))
        // 2. Negative length
        assertFalse(SampleDataQueueNative.copyBetweenDirectBuffers(source, 0, target, 0, -5))
        // 3. Length exceeds source capacity
        assertFalse(SampleDataQueueNative.copyBetweenDirectBuffers(source, 18, target, 0, 5))
        // 4. Length exceeds target capacity
        assertFalse(SampleDataQueueNative.copyBetweenDirectBuffers(source, 0, target, 18, 5))
    }
}
