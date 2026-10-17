package com.nuvio.tv.data.mdblist

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MdbListTimestampTest {
    @Test
    fun fractionalActivityTimestampsDecodeOnAndroid() {
        val value = "2026-09-06T14:12:26.090Z"
        assertEquals(1788703946090L, Instant.parse(value).toEpochMilli())
        assertEquals(1788703946090L, mdbListTimestamp(value))
        assertEquals(value, decodeMdbListActivities("""{"server_time":"$value","watched_at":null}""").serverTime)
    }
}
