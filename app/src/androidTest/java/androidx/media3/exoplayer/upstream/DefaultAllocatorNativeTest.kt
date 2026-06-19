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

    @Test
    fun testArenaPoolExhaustionAndReplenishment() {
        val segmentSize = 65536
        
        // Fetch arena field addresses via reflection
        val fieldBase = DefaultAllocatorNative::class.java.getDeclaredField("arenaBaseAddress")
        fieldBase.isAccessible = true
        
        val fieldEnd = DefaultAllocatorNative::class.java.getDeclaredField("arenaEndAddress")
        fieldEnd.isAccessible = true

        // Trigger initialization of the Arena by making one allocation
        val firstAlloc = DefaultAllocatorNative.createAllocation(segmentSize)
        assertNotNull(firstAlloc)
        DefaultAllocatorNative.freeAllocation(firstAlloc!!)

        val arenaBaseAddress = fieldBase.get(null) as Long
        val arenaEndAddress = fieldEnd.get(null) as Long

        // If JNI is not available (e.g. running on JVM / non-Android environment), skip test
        if (arenaBaseAddress == 0L) {
            return
        }

        // Exhaust the Arena pool (capacity is 512 chunks)
        val arenaAllocations = (1..512).map {
            val alloc = DefaultAllocatorNative.createAllocation(segmentSize)
            assertNotNull("Allocation should succeed", alloc)
            assertTrue("Should belong to Arena pool", alloc!!.nativeHandle in arenaBaseAddress until arenaEndAddress)
            alloc
        }

        // Allocate one more 64 KB segment. This should overflow to dynamic allocation path
        val dynamicAlloc = DefaultAllocatorNative.createAllocation(segmentSize)
        assertNotNull("Overflow allocation should succeed", dynamicAlloc)
        assertTrue(
            "Overflow allocation must NOT belong to Arena pool",
            dynamicAlloc!!.nativeHandle < arenaBaseAddress || dynamicAlloc.nativeHandle >= arenaEndAddress
        )

        // Free one Arena allocation to replenish the pool
        val firstArenaAlloc = arenaAllocations[0]
        DefaultAllocatorNative.freeAllocation(firstArenaAlloc)

        // Allocate again; it should be fetched from the replenished Arena pool
        val recycledAlloc = DefaultAllocatorNative.createAllocation(segmentSize)
        assertNotNull("Recycled allocation should succeed", recycledAlloc)
        assertTrue(
            "Recycled allocation should belong to Arena pool",
            recycledAlloc!!.nativeHandle in arenaBaseAddress until arenaEndAddress
        )

        // Clean up everything
        arenaAllocations.drop(1).forEach { DefaultAllocatorNative.freeAllocation(it) }
        DefaultAllocatorNative.freeAllocation(recycledAlloc)
        DefaultAllocatorNative.freeAllocation(dynamicAlloc)
    }
}
