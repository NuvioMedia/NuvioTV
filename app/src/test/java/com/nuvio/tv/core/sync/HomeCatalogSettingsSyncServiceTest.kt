package com.nuvio.tv.core.sync

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCatalogSettingsSyncServiceTest {
    @Test
    fun sharedSettingsMergePreservesUnknownRemoteFields() {
        val remote = buildJsonObject {
            put("future_setting", "preserved")
            put("hide_unreleased_content", false)
        }
        val local = buildJsonObject {
            put("hide_unreleased_content", true)
        }

        val merged = mergeHomeCatalogSettingsJson(remote, local)

        assertEquals("preserved", merged.getValue("future_setting").jsonPrimitive.content)
        assertEquals(true, merged.getValue("hide_unreleased_content").jsonPrimitive.content.toBoolean())
    }
}
