package com.nuvio.tv.core.runtime

import android.app.ActivityManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DeviceCapabilitiesTest {
    @Before
    fun resetDeviceSnapshot() {
        DeviceCapabilities.clearCachedMemoryForTests()
    }

    @After
    fun clearDeviceSnapshot() {
        DeviceCapabilities.clearCachedMemoryForTests()
    }

    @Test
    fun lowRamFlagAndSmallHeapsTakePriorityOverNominalPhysicalMemory() {
        assertEquals(
            DeviceResourceTier.LOW_RAM,
            DeviceCapabilities.resourceTier(8L * GIB, 512, isLowRamDevice = true),
        )
        assertEquals(
            DeviceResourceTier.LOW_RAM,
            DeviceCapabilities.resourceTier(2L * GIB, 256, isLowRamDevice = false),
        )
        assertEquals(
            DeviceResourceTier.LOW_RAM,
            DeviceCapabilities.resourceTier(4L * GIB, 192, isLowRamDevice = false),
        )
    }

    @Test
    fun memoryAndHeapThresholdsClassifyBalancedAndHighCapacityDevices() {
        assertEquals(
            DeviceResourceTier.BALANCED,
            DeviceCapabilities.resourceTier(3L * GIB, 384, isLowRamDevice = false),
        )
        assertEquals(
            DeviceResourceTier.BALANCED,
            DeviceCapabilities.resourceTier(4L * GIB, 256, isLowRamDevice = false),
        )
        assertEquals(
            DeviceResourceTier.HIGH_CAPACITY,
            DeviceCapabilities.resourceTier(4L * GIB, 384, isLowRamDevice = false),
        )
        assertEquals(
            DeviceResourceTier.LOW_RAM,
            DeviceCapabilities.resourceTier(
                totalMemoryBytes = 8L * GIB,
                memoryClassMb = 512,
                isLowRamDevice = false,
                maxAppHeapBytes = 192L * MIB,
            ),
        )
    }

    @Test
    fun unknownMemoryRemainsExplicitUntilThePerformancePolicyChoosesSafeDefaults() {
        assertEquals(
            DeviceResourceTier.UNKNOWN,
            DeviceCapabilities.resourceTier(null, null, null),
        )
    }

    @Test
    fun advertisedDolbyVisionProfilesAreMappedByTheCentralCapabilityProvider() {
        val profiles = listOf(
            android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb,
            android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn,
            android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt,
        )

        assertEquals(
            DolbyVisionDecoderCapabilities(profile7 = true, profile5 = true, profile8 = true),
            DeviceCapabilities.dolbyVisionDecoderCapabilities(profiles),
        )
        assertEquals(
            DolbyVisionDecoderCapabilities(),
            DeviceCapabilities.dolbyVisionDecoderCapabilities(emptyList()),
        )
    }

    @Test
    fun physicalMemoryFactsAreReadOnceAndSharedAcrossConsumers() {
        val context = mockk<Context>()
        val activityManager = mockk<ActivityManager>()
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns activityManager
        every { activityManager.memoryClass } returns 384
        every { activityManager.isLowRamDevice } returns false
        every { activityManager.getMemoryInfo(any()) } answers {
            firstArg<ActivityManager.MemoryInfo>().totalMem = 4L * GIB
        }

        val first = DeviceCapabilities.memory(context)
        val second = DeviceCapabilities.memory(context)

        assertEquals(4L * GIB, first.totalMemoryBytes)
        assertEquals(first, second)
        verify(exactly = 1) { activityManager.getMemoryInfo(any()) }
        verify(exactly = 1) { activityManager.memoryClass }
        verify(exactly = 1) { activityManager.isLowRamDevice }
    }

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
        const val MIB = 1024L * 1024L
    }
}
