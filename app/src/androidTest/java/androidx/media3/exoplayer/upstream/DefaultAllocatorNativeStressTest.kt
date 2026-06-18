package androidx.media3.exoplayer.upstream

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DefaultAllocatorNativeStressTest {

    @Test
    fun testConcurrentAllocationAndRelease() {
        val numThreads = 8
        val allocationsPerThread = 500
        val allocationSize = 16384 // 16 KB

        val executor = Executors.newFixedThreadPool(numThreads)

        for (t in 0 until numThreads) {
            executor.submit {
                for (i in 0 until allocationsPerThread) {
                    val allocation = DefaultAllocatorNative.createAllocation(allocationSize)
                    assertNotNull("Stress allocation should succeed", allocation)
                    assertNotNull("Allocation buffer should not be null", allocation!!.buffer)
                    assertTrue("Allocation buffer must be direct", allocation.buffer!!.isDirect)
                    assertEquals("Buffer capacity should match size", allocationSize, allocation.buffer!!.capacity())
                    assertTrue("Allocation handle must be non-zero", allocation.nativeHandle != 0L)
                    
                    // Immediately free it
                    DefaultAllocatorNative.freeAllocation(allocation)
                    assertEquals("Handle must be cleared to 0 immediately", 0L, allocation.nativeHandle)
                }
            }
        }

        executor.shutdown()
        val finished = executor.awaitTermination(30, TimeUnit.SECONDS)
        assertTrue("Stress test threads should terminate inside timeout", finished)

        // Wait an additional 3.5 seconds to ensure all queued background native deallocations 
        // (which are scheduled with a 2-second delay) execute and complete successfully without crashing.
        Thread.sleep(3500)
    }
}
