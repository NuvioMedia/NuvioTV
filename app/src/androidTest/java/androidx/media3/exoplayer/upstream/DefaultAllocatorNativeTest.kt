package androidx.media3.exoplayer.upstream

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultAllocatorNativeTest {

    @Test
    fun testAllocationAndReleaseLifecycle() {
        val size = 65536
        // Allocate native memory
        val allocation = DefaultAllocatorNative.createAllocation(size)

        // Assert allocation is successful and direct
        assertNotNull("Allocation should not be null", allocation)
        assertNotNull("Allocation buffer should not be null", allocation!!.buffer)
        assertTrue("Allocation buffer must be direct", allocation.buffer!!.isDirect)
        assertEquals("Allocation buffer capacity should match size", size, allocation.buffer!!.capacity())
        assertTrue("Allocation native handle should be non-zero", allocation.nativeHandle != 0L)

        // Free allocation
        DefaultAllocatorNative.freeAllocation(allocation)

        // Verify that java-side handle is cleared immediately to prevent double-free
        assertEquals("Allocation handle should be cleared immediately", 0L, allocation.nativeHandle)

        // Let's also check allocating and freeing multiple buffers
        val allocation2 = DefaultAllocatorNative.createAllocation(32768)
        assertNotNull(allocation2)
        assertTrue(allocation2!!.buffer!!.isDirect)
        DefaultAllocatorNative.freeAllocation(allocation2)
    }

    @Test
    fun testAllocationZeroAndNegativeSize() {
        // Size 0 should return null
        val allocationZero = DefaultAllocatorNative.createAllocation(0)
        assertNull("Allocation with size 0 should be null", allocationZero)

        // Negative size should return null
        val allocationNeg = DefaultAllocatorNative.createAllocation(-100)
        assertNull("Allocation with negative size should be null", allocationNeg)
    }
}
