package com.nuvio.tv.ui.screens.player.iec

import org.junit.Assert.assertEquals
import org.junit.Test

class IecOutputLatencyTest {

    @Test
    fun subtractsTheOneSecondBufferAndKeepsTheHalTail() {
        assertEquals(40_000L, iecOutputLatencyUs(reportedLatencyMs = 1_040, bufferSizeUs = 1_000_000L))
        assertEquals(80_000L, iecOutputLatencyUs(reportedLatencyMs = 1_080, bufferSizeUs = 1_000_000L))
        assertEquals(20_000L, iecOutputLatencyUs(reportedLatencyMs = 220, bufferSizeUs = 200_000L))
    }

    @Test
    fun doesNotTreatTheWholeBufferAsLatency() {
        assertEquals(0L, iecOutputLatencyUs(reportedLatencyMs = 1_000, bufferSizeUs = 1_000_000L))
        assertEquals(0L, iecOutputLatencyUs(reportedLatencyMs = 1_040, bufferSizeUs = 1_040_000L))
        assertEquals(0L, iecOutputLatencyUs(reportedLatencyMs = 40, bufferSizeUs = 1_000_000L))
    }

    @Test
    fun keepsAReportedHalLatencyWhenTheBufferSizeIsUnknown() {
        assertEquals(40_000L, iecOutputLatencyUs(reportedLatencyMs = 40, bufferSizeUs = 0L))
        assertEquals(40_000L, iecOutputLatencyUs(reportedLatencyMs = 40, bufferSizeUs = -1L))
    }

    @Test
    fun ignoresMissingAndAbsurdReports() {
        assertEquals(0L, iecOutputLatencyUs(reportedLatencyMs = 0, bufferSizeUs = 1_000_000L))
        assertEquals(0L, iecOutputLatencyUs(reportedLatencyMs = -20, bufferSizeUs = 1_000_000L))
        assertEquals(
            IEC_MAX_OUTPUT_LATENCY_US,
            iecOutputLatencyUs(
                reportedLatencyMs = 6_000,
                bufferSizeUs = 1_000_000L
            )
        )
        assertEquals(
            0L,
            iecOutputLatencyUs(
                reportedLatencyMs = 6_001,
                bufferSizeUs = 1_000_000L
            )
        )
    }
}
