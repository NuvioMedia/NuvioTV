package androidx.media3.exoplayer.upstream

import androidx.media3.common.NuvioEngineConfig
import androidx.media3.exoplayer.source.SampleDataQueueNative
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull

@RunWith(AndroidJUnit4::class)
class DefaultAllocatorNativeLeakTest {

    @Before
    fun setUp() {
        NuvioEngineConfig.set(NuvioEngineConfig.nuvioMode())
    }

    @After
    fun tearDown() {
        NuvioEngineConfig.set(NuvioEngineConfig.stockMode())
    }

    @Test
    fun testWeakReferenceCacheDoesNotLeak() {
        // Force GC before test
        runGc()
        val initialMemory = getUsedMemory()

        // Create thousands of temporary ByteBuffers and cache their addresses
        for (i in 0..10000) {
            val tempBuffer = ByteBuffer.allocateDirect(1024)
            val address = SampleDataQueueNative.getDirectBufferAddressCached(tempBuffer)
            assertNotNull(address)
        }

        // Force GC to reclaim all temporary ByteBuffers
        runGc()
        val finalMemory = getUsedMemory()

        // Since WeakReferences are used, memory should be collected. 
        // We check that used memory hasn't grown by more than a reasonable threshold (e.g., 2MB)
        val deltaMemoryMb = (finalMemory - initialMemory) / (1024.0 * 1024.0)
        assertTrue("Memory leak detected in WeakReference cache! Delta: $deltaMemoryMb MB", deltaMemoryMb < 2.0)
    }

    @Test
    fun testDynamicAllocationDeallocationLeak() {
        runGc()
        val initialMemory = getUsedMemory()

        // Perform rapid dynamic allocations (sizes other than 64KB Arena chunk size)
        // to force the dynamic JNI path.
        val iterations = 1000
        val size = 75000 // 75 KB (forces dynamic allocation)

        for (i in 0 until iterations) {
            val allocation = DefaultAllocatorNative.createAllocation(size)
            assertNotNull(allocation)
            DefaultAllocatorNative.freeAllocation(allocation!!)
        }

        // Wait for deferred deallocations to complete (since dynamic allocations use 2s delay)
        Thread.sleep(2500)

        runGc()
        val finalMemory = getUsedMemory()

        val deltaMemoryMb = (finalMemory - initialMemory) / (1024.0 * 1024.0)
        assertTrue("Memory leak detected in dynamic allocation path! Delta: $deltaMemoryMb MB", deltaMemoryMb < 2.0)
    }

    private fun runGc() {
        for (i in 0..3) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            Thread.sleep(50)
        }
    }

    private fun getUsedMemory(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
}
