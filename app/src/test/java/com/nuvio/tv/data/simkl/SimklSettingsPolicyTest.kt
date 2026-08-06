package com.nuvio.tv.data.simkl

import org.junit.Assert.assertEquals
import org.junit.Test

class SimklSettingsPolicyTest {
    @Test
    fun `missing activity watermark does not fetch settings`() {
        assertEquals(
            SimklSettingsRefreshAction.NONE,
            simklSettingsRefreshAction(SimklAuthState(), null)
        )
    }

    @Test
    fun `first activity watermark is recorded after sign in settings fetch`() {
        assertEquals(
            SimklSettingsRefreshAction.RECORD_WATERMARK,
            simklSettingsRefreshAction(
                SimklAuthState(hasFetchedUserSettings = true),
                "2026-07-22T08:48:07Z"
            )
        )
    }

    @Test
    fun `changed activity watermark fetches settings and matching watermark skips`() {
        val state = SimklAuthState(
            hasFetchedUserSettings = true,
            settingsActivityWatermark = "2026-07-22T08:48:07Z"
        )

        assertEquals(
            SimklSettingsRefreshAction.NONE,
            simklSettingsRefreshAction(state, "2026-07-22T08:48:07Z")
        )
        assertEquals(
            SimklSettingsRefreshAction.FETCH,
            simklSettingsRefreshAction(state, "2026-07-22T09:12:30Z")
        )
    }

    @Test
    fun `legacy auth state fetches once before recording a watermark`() {
        assertEquals(
            SimklSettingsRefreshAction.FETCH,
            simklSettingsRefreshAction(
                SimklAuthState(username = "Nuvio User"),
                "2026-07-22T08:48:07Z"
            )
        )
    }
}
