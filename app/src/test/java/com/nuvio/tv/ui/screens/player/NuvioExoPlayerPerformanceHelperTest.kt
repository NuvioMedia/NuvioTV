package com.nuvio.tv.ui.screens.player

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.Assert.assertEquals
import org.junit.Test

class NuvioExoPlayerPerformanceHelperTest {

    private val gb = 1024L * 1024L * 1024L

    @Test
    fun `test default fallback values when RAM is zero or unknown`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns 0L

        assertEquals("Unknown", helperSpy.getFriendlyRamLabel(context))
        assertEquals(400, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 1 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 1GB physical RAM (reports ~900MB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (0.9 * gb).toLong()

        assertEquals("1 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(150, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 1_5 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 1.5GB physical RAM (reports ~1.3GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (1.3 * gb).toLong()

        assertEquals("1.5 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(200, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 2 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 2GB physical RAM (reports ~1.7GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (1.7 * gb).toLong()

        assertEquals("2 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(400, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 3 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 3GB physical RAM (reports ~2.6GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (2.6 * gb).toLong()

        assertEquals("3 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(800, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 4 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 4GB physical RAM (reports ~3.6GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (3.6 * gb).toLong()

        assertEquals("4 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(1200, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 6 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 6GB physical RAM (reports ~5.4GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (5.4 * gb).toLong()

        assertEquals("6 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(1600, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 8 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 8GB physical RAM (reports ~7.4GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (7.4 * gb).toLong()

        assertEquals("8 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(2048, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 12 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 12GB physical RAM (reports ~11.0GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (11.0 * gb).toLong()

        assertEquals("12 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(2048, helperSpy.getSafeNativeMemoryLimitMb(context))
    }

    @Test
    fun `test 16 GB RAM tier classification`() {
        val helperSpy = spyk(NuvioExoPlayerPerformanceHelper)
        val context = mockk<Context>()

        // 16GB physical RAM (reports ~14.8GB)
        every { helperSpy.getDevicePhysicalRamBytes(any()) } returns (14.8 * gb).toLong()

        assertEquals("16 GB", helperSpy.getFriendlyRamLabel(context))
        assertEquals(2048, helperSpy.getSafeNativeMemoryLimitMb(context))
    }
}
