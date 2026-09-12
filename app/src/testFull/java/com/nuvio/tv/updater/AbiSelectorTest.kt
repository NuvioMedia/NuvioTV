package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbiSelectorTest {
    private fun asset(name: String) = GitHubAssetDto(name, "https://example.invalid/$name")
    private fun choose(names: List<String>, vararg abis: String) =
        AbiSelector.chooseBestApkAsset(names.map(::asset), abis.toList())?.name

    @Test fun `x86 never selects x86_64 even as singleton`() {
        assertNull(choose(listOf("app-x86_64-release.apk"), "x86"))
        assertNull(choose(listOf("app-x86_64debug.apk"), "x86"))
        assertEquals("app-x86.apk", choose(listOf("app-x86_64.apk", "app-x86.apk"), "x86"))
    }
    @Test fun `device preference wins over release asset order`() {
        val names = listOf("app-armeabi-v7a.apk", "app-arm64-v8a.apk", "app-universal.apk")
        for (ordered in listOf(names, names.reversed())) {
            assertEquals("app-arm64-v8a.apk", choose(ordered, "arm64-v8a", "armeabi-v7a"))
            assertEquals("app-armeabi-v7a.apk", choose(ordered, "armeabi-v7a", "arm64-v8a"))
        }
    }
    @Test fun `singleton requires matching ABI or explicit universal token`() {
        assertEquals("app-arm64-v8a.apk", choose(listOf("app-arm64-v8a.apk"), "arm64-v8a"))
        assertNull(choose(listOf("app-arm64-v8a.apk"), "x86_64"))
        assertNull(choose(listOf("app-release.apk"), "arm64-v8a"))
        assertNull(choose(listOf("app-small.apk"), "arm64-v8a"))
        assertNull(choose(listOf("app-universally.apk"), "arm64-v8a"))
        assertEquals("app-universal.apk", choose(listOf("app-universal.apk"), "x86_64"))
    }
    @Test fun `missing compatible ABI only permits explicit universal fallback`() {
        assertNull(choose(listOf("app-arm64-v8a.apk", "app-armeabi-v7a.apk"), "x86_64"))
        assertEquals("app-all.apk", choose(listOf("app-arm64-v8a.apk", "app-all.apk"), "x86_64"))
        assertNull(choose(listOf("app-universal-arm64-v8a.apk"), "x86"))
        assertNull(choose(listOf("app-arm64-v8a.apk")))
        assertEquals("app-universal.apk", choose(listOf("app-universal.apk")))
    }
    @Test fun `rejects misleading and ambiguous filenames`() {
        assertNull(choose(listOf("app-notx86.apk", "app-x86debug.apk"), "x86"))
        assertNull(choose(listOf("app-x86-arm64-v8a.apk"), "x86"))
        assertNull(choose(listOf("app-x86.apk.sig"), "x86"))
        assertEquals("APP_X86_64.APK", choose(listOf("APP_X86_64.APK"), "x86_64"))
    }
}
