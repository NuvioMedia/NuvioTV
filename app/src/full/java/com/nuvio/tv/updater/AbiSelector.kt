package com.nuvio.tv.updater

import android.os.Build
import com.nuvio.tv.data.remote.dto.GitHubAssetDto

internal object AbiSelector {

    private val knownAbis = listOf(
        "arm64-v8a",
        "armeabi-v7a",
        "x86_64",
        "x86"
    )

    fun chooseBestApkAsset(
        assets: List<GitHubAssetDto>,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()
    ): GitHubAssetDto? {
        val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return null
        // Prefer exact ABI match (in device preference order)
        for (abi in supportedAbis) {
            if (abi !in knownAbis) continue
            val candidate = apkAssets.firstOrNull { asset ->
                namedAbis(asset.name) == setOf(abi)
            }
            if (candidate != null) return candidate
        }

        // Fallback to a universal APK if present
        val universal = apkAssets.firstOrNull {
            namedAbis(it.name).isEmpty() &&
                Regex("(?:^|[-_.])(?:universal|all)(?=[-_.]|$)", RegexOption.IGNORE_CASE)
                    .containsMatchIn(it.name)
        }
        if (universal != null) return universal

        // Unknown filenames do not establish ABI compatibility.
        return null
    }

    private fun namedAbis(name: String): Set<String> {
        // Match the longest token first so x86_64 is never interpreted as x86.
        val pattern = Regex(
            "(?:^|[-_.])(arm64-v8a|armeabi-v7a|x86_64|x86(?!_64))(?=[-_.]|$)",
            RegexOption.IGNORE_CASE
        )
        return pattern.findAll(name).map { it.groupValues[1].lowercase() }.toSet()
    }
}
