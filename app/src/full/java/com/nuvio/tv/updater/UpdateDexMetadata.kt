package com.nuvio.tv.updater

import android.os.Build
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

/**
 * Reads the dex metadata (.dm) files the build embeds under assets/updater_dm. Installing an update together with the
 * .dm for the device's API level lets ART compile the baseline profile during the install, instead of waiting for
 * background dexopt.
 */
internal object UpdateDexMetadata {
    private const val ASSET_DIR = "assets/updater_dm/"
    private const val INDEX_FILE = "index.properties"

    /** Extracts the .dm matching [sdkInt] from [apkFile] into [destination], or returns null when there is none. */
    fun extract(apkFile: File, sdkInt: Int, destination: File): File? {
        // Dex metadata in install sessions is supported from Android 9.
        if (sdkInt < Build.VERSION_CODES.P) return null
        return runCatching {
            ZipFile(apkFile).use { zip ->
                val indexEntry = zip.getEntry(ASSET_DIR + INDEX_FILE) ?: return null
                val index = Properties().apply { zip.getInputStream(indexEntry).use(::load) }
                val fileName = selectFileName(index, sdkInt) ?: return null
                val entry = zip.getEntry(ASSET_DIR + fileName) ?: return null
                destination.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                destination
            }
        }.getOrNull()
    }

    /** Picks the file listed for [sdkInt]; levels above every listed one use the open-ended entry. */
    fun selectFileName(index: Properties, sdkInt: Int): String? {
        index.getProperty(sdkInt.toString())?.let { return it }
        val listedLevels = index.stringPropertyNames().mapNotNull { it.toIntOrNull() }
        val highestListed = listedLevels.filter { it != Int.MAX_VALUE }.maxOrNull() ?: return null
        return if (sdkInt > highestListed) index.getProperty(Int.MAX_VALUE.toString()) else null
    }
}
