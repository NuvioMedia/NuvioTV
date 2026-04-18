package com.nuvio.tv.core.plugin.cloudstream

import android.content.Context
import android.os.Build
import android.util.Log
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis
import com.nuvio.tv.core.plugin.TestDiagnostics
import dalvik.system.DexClassLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExtExtensionLoader"

/**
 * Checks whether an instance looks like a CloudStream plugin by checking if it has
 * plugin-like methods. Covers three cases:
 * 1. Our Plugin class: load(Activity?), load(Context), getRegisteredMainAPIs()
 * 2. Library's BasePlugin: load() (no-arg), registerMainAPI()
 * 3. Foreign plugins with similar signatures
 */
private fun looksLikePlugin(instance: Any): Boolean {
    // Fast path: check if it's an instance of the library's BasePlugin
    if (instance is BasePlugin) return true

    val clazz = instance.javaClass
    // Check for any form of load() method
    val hasLoad = try {
        clazz.getMethod("load", Context::class.java) != null
    } catch (_: NoSuchMethodException) {
        try {
            clazz.getMethod("load", android.app.Activity::class.java) != null
        } catch (_: NoSuchMethodException) {
            try {
                // BasePlugin-style no-arg load()
                clazz.getMethod("load") != null
            } catch (_: NoSuchMethodException) {
                false
            }
        }
    }
    val hasRegisteredAPIs = try {
        clazz.getMethod("getRegisteredMainAPIs") != null
    } catch (_: NoSuchMethodException) {
        false
    }
    return hasLoad || hasRegisteredAPIs
}

/**
 * Wraps a plugin instance loaded from a foreign classloader or with a non-standard base class.
 * Handles three plugin patterns:
 * 1. Our Plugin: load(Activity?), load(Context), getRegisteredMainAPIs()
 * 2. Library's BasePlugin: load() no-arg, registers to APIHolder.allProviders + extractorApis
 * 3. Foreign plugins with similar signatures
 */
private class ReflectivePluginWrapper(private val foreignInstance: Any) : Plugin() {
    override fun load(activity: android.app.Activity?) {
        load(activity as? Context ?: AcraApplication.context ?: return)
    }

    override fun load(context: Context) {
        // Snapshot global registries before load() to detect what this plugin adds
        val providersBefore = synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.toList()
        }
        val extractorsBefore = extractorApis.toList()

        val clazz = foreignInstance.javaClass
        var loaded = false

        // Try load(Context) first
        try {
            val m = clazz.getMethod("load", Context::class.java)
            m.invoke(foreignInstance, context)
            loaded = true
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            if (cause is ClassCastException) {
                Log.d(TAG, "ReflectivePluginWrapper: load(Context) got ClassCastException, retrying with null Activity")
                try {
                    val m = clazz.getMethod("load", android.app.Activity::class.java)
                    m.invoke(foreignInstance, null)
                    loaded = true
                } catch (e2: Exception) {
                    Log.w(TAG, "ReflectivePluginWrapper: load(Activity) also failed: ${e2.message}")
                }
            } else {
                Log.w(TAG, "ReflectivePluginWrapper: load(Context) threw: ${cause?.message ?: e.message}")
            }
        } catch (_: NoSuchMethodException) {
            // Try load(Activity?) next
            try {
                val m = clazz.getMethod("load", android.app.Activity::class.java)
                m.invoke(foreignInstance, null)
                loaded = true
            } catch (_: NoSuchMethodException) {
                // Try no-arg load() (BasePlugin pattern)
                try {
                    val m = clazz.getMethod("load")
                    m.invoke(foreignInstance)
                    loaded = true
                    Log.d(TAG, "ReflectivePluginWrapper: loaded via no-arg load()")
                } catch (e: Exception) {
                    Log.w(TAG, "ReflectivePluginWrapper: load() (no-arg) failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ReflectivePluginWrapper: load(Activity) failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ReflectivePluginWrapper: load() failed: ${e.message}")
        }

        // First try reading local registration lists (our Plugin pattern)
        try {
            val getter = clazz.getMethod("getRegisteredMainAPIs")
            @Suppress("UNCHECKED_CAST")
            val apis = getter.invoke(foreignInstance) as? List<MainAPI> ?: emptyList()
            apis.forEach { registerMainAPI(it) }
        } catch (_: Exception) {
            // No local list — check global APIHolder for newly registered providers
            // (BasePlugin.registerMainAPI adds to APIHolder.allProviders)
            val newProviders = synchronized(APIHolder.allProviders) {
                APIHolder.allProviders.toList()
            } - providersBefore.toSet()
            if (newProviders.isNotEmpty()) {
                Log.d(TAG, "ReflectivePluginWrapper: found ${newProviders.size} providers via APIHolder")
                newProviders.forEach { registerMainAPI(it) }
            }
        }

        try {
            val getter = clazz.getMethod("getRegisteredExtractorAPIs")
            @Suppress("UNCHECKED_CAST")
            val extractors = getter.invoke(foreignInstance) as? List<ExtractorApi> ?: emptyList()
            extractors.forEach { registerExtractorAPI(it) }
        } catch (_: Exception) {
            // Check global extractorApis for newly registered extractors
            val newExtractors = extractorApis.toList() - extractorsBefore.toSet()
            if (newExtractors.isNotEmpty()) {
                Log.d(TAG, "ReflectivePluginWrapper: found ${newExtractors.size} extractors via extractorApis")
                newExtractors.forEach { registerExtractorAPI(it) }
            }
        }
    }
}

private const val MAX_DEX_SIZE = 10 * 1024 * 1024L // 10MB max per .cs3 file

/**
 * Manages downloading, loading, and caching of DEX-based external extensions (.cs3 files).
 */
@Singleton
class ExternalExtensionLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val extractorRegistry: ExternalExtractorRegistry
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Cache of loaded MainAPI instances by scraper ID */
    private val apiCache = ConcurrentHashMap<String, MainAPI>()

    /** Cache of loaded class loaders by scraper ID */
    private val classLoaderCache = ConcurrentHashMap<String, DexClassLoader>()

    /** Tracks which scraper IDs have already been scanned for extractors */
    private val extractorPreloadedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val extensionsDir: File
        get() = File(context.filesDir, "cs_extensions").also { it.mkdirs() }

    private val codeCacheDir: File
        get() = File(context.codeCacheDir, "cs_dex_cache").also { it.mkdirs() }

    /** Sanitize scraper ID for use as a filename (colons are not safe on all filesystems). */
    private fun safeFileName(scraperId: String): String =
        scraperId.replace(':', '_').replace('/', '_')

    /**
     * Download a .cs3 DEX file for the given scraper.
     * Returns the local file path, or null on failure.
     */
    suspend fun downloadExtension(scraperId: String, downloadUrl: String): File? = withContext(Dispatchers.IO) {
        try {
            val targetFile = File(extensionsDir, "${safeFileName(scraperId)}.cs3")

            // Remove existing read-only file before writing (DEX files are set
            // read-only for API 28+ compat, so overwriting would fail with EACCES)
            if (targetFile.exists()) {
                targetFile.setWritable(true)
                targetFile.delete()
            }

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "NuvioTV/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download extension $scraperId: HTTP ${response.code}")
                    return@withContext null
                }

                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0
                if (contentLength > MAX_DEX_SIZE) {
                    Log.w(TAG, "Extension $scraperId too large: $contentLength bytes")
                    return@withContext null
                }

                val bytes = response.body?.bytes() ?: return@withContext null
                if (bytes.size > MAX_DEX_SIZE) {
                    Log.w(TAG, "Extension $scraperId too large: ${bytes.size} bytes")
                    return@withContext null
                }

                targetFile.writeBytes(bytes)

                // Fix for Android API 28+: DEX files must be read-only
                // Writing writable DEX files is blocked on newer Android versions
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    targetFile.setReadOnly()
                    Log.d(TAG, "Set DEX file read-only for API ${Build.VERSION.SDK_INT}")
                }

                Log.d(TAG, "Downloaded extension $scraperId: ${bytes.size} bytes -> ${targetFile.absolutePath}")
                targetFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading extension $scraperId: ${e.message}", e)
            null
        }
    }

    /**
     * Load a .cs3 DEX file and return the MainAPI instance(s) registered by the plugin.
     */
    fun loadExtension(scraperId: String): List<MainAPI> {
        // Check cache first
        apiCache[scraperId]?.let { return listOf(it) }

        val dexFile = File(extensionsDir, "${safeFileName(scraperId)}.cs3")
        if (!dexFile.exists()) {
            Log.e(TAG, "DEX file not found for $scraperId: ${dexFile.absolutePath}")
            return emptyList()
        }

        // Ensure DEX file is read-only (fix for existing downloads on API 28+)
        ensureDexReadOnly(dexFile)

        return try {
            val classLoader = DexClassLoader(
                dexFile.absolutePath,
                codeCacheDir.absolutePath,
                null,
                context.classLoader
            )
            classLoaderCache[scraperId] = classLoader

            // Check for critical class shadowing
            try {
                @Suppress("DEPRECATION")
                val inspectDex = dalvik.system.DexFile(dexFile)
                val allEntries = inspectDex.entries().toList()
                inspectDex.close()
                val criticalShadows = allEntries.filter { className ->
                    className == "com.lagradost.cloudstream3.MainActivityKt" ||
                    className == "com.lagradost.cloudstream3.MainAPIKt" ||
                    className == "com.lagradost.cloudstream3.utils.ExtractorApiKt" ||
                    className == "com.lagradost.cloudstream3.utils.AppUtilsKt"
                }
                if (criticalShadows.isNotEmpty()) {
                    Log.w(TAG, "Extension $scraperId shadows critical classes: $criticalShadows")
                }
            } catch (_: Exception) {}

            // Find and instantiate the plugin class
            val plugin = findAndLoadPlugin(classLoader, dexFile)
            if (plugin == null) {
                Log.e(TAG, "No @CloudstreamPlugin class found in $scraperId")
                return emptyList()
            }

            // Ensure global stubs are initialized for extensions
            AcraApplication.context = context
            extractorRegistry.installGlobal()

            // Call load() to trigger registerMainAPI() calls.
            // Dispatch on Context so plugins that override load(Context) — upstream's
            // primary overload — get their impl invoked. Plugins that only override
            // load(Activity?) or no-arg load() still work: stub's load(Context) casts
            // the arg to Activity? and chains through.
            val activity = AcraApplication.getActivity()
            try {
                plugin.load((activity as Context?) ?: context)
            } catch (e: Exception) {
                Log.w(TAG, "plugin.load() threw (partial load, ${plugin.registeredMainAPIs.size} APIs so far): ${e.message}", e)
            } catch (e: Error) {
                val missingClass = extractMissingClassName(e)
                if (missingClass != null) {
                    Log.w(TAG, "plugin.load() MISSING CLASS: $missingClass (${plugin.registeredMainAPIs.size} APIs so far)", e)
                } else {
                    Log.w(TAG, "plugin.load() linkage error (partial load, ${plugin.registeredMainAPIs.size} APIs so far): ${e.message}", e)
                }
            }

            // Register any extractors the plugin provides
            extractorRegistry.registerAll(plugin.registeredExtractorAPIs)

            var apis = plugin.registeredMainAPIs
            Log.d(TAG, "After load(): ${apis.size} MainAPIs, ${plugin.registeredExtractorAPIs.size} extractors")

            // FALLBACK: If load() registered 0 APIs or 0 extractors, scan DEX directly.
            if (apis.isEmpty() || plugin.registeredExtractorAPIs.isEmpty()) {
                Log.d(TAG, "Fallback: scanning DEX for MainAPI/ExtractorApi subclasses in $scraperId")
                val fallbackApis = mutableListOf<MainAPI>()
                val fallbackExtractors = mutableListOf<ExtractorApi>()
                try {
                    @Suppress("DEPRECATION")
                    val inspectDex = dalvik.system.DexFile(dexFile)
                    val allClasses = inspectDex.entries().toList()
                    inspectDex.close()

                    val candidates = allClasses.filter { className ->
                        !className.contains('$') &&
                        !className.contains("Plugin") &&
                        !className.contains("Fragment") &&
                        className.startsWith("com.")
                    }

                    for (className in candidates) {
                        try {
                            val clazz = classLoader.loadClass(className)
                            if (apis.isEmpty()
                                && MainAPI::class.java.isAssignableFrom(clazz)
                                && !java.lang.reflect.Modifier.isAbstract(clazz.modifiers)
                                && !clazz.isInterface) {
                                val instance = clazz.getDeclaredConstructor().newInstance() as MainAPI
                                fallbackApis.add(instance)
                                Log.d(TAG, "Fallback found MainAPI: ${instance.name} ($className)")
                            } else if (ExtractorApi::class.java.isAssignableFrom(clazz)
                                && !java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) {
                                val instance = clazz.getDeclaredConstructor().newInstance() as ExtractorApi
                                fallbackExtractors.add(instance)
                                Log.d(TAG, "Fallback found ExtractorApi: ${instance.name} (${instance.mainUrl})")
                            }
                        } catch (e: Error) {
                            val missing = extractMissingClassName(e)
                            if (missing != null) {
                                Log.w(TAG, "Fallback skip $className: MISSING $missing")
                            }
                        } catch (_: Exception) {
                            // Skip classes that can't be instantiated
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback DEX scan failed: ${e.message}")
                }

                if (fallbackApis.isNotEmpty()) {
                    Log.d(TAG, "Fallback found ${fallbackApis.size} MainAPIs")
                    apis = fallbackApis
                }
                if (fallbackExtractors.isNotEmpty()) {
                    Log.d(TAG, "Fallback found ${fallbackExtractors.size} ExtractorApis")
                    extractorRegistry.registerAll(fallbackExtractors)
                }
            }

            apis.forEach { api ->
                apiCache["$scraperId:${api.name}"] = api
            }

            // Also cache the first API under the plain scraper ID
            if (apis.isNotEmpty()) {
                apiCache[scraperId] = apis.first()
            }

            Log.d(TAG, "Loaded extension $scraperId: ${apis.size} providers (${apis.joinToString { it.name }})")
            apis
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load extension $scraperId: ${e.message}", e)
            emptyList()
        } catch (e: Error) {
            val missingClass = extractMissingClassName(e)
            if (missingClass != null) {
                Log.e(TAG, "Failed to load extension $scraperId: MISSING CLASS: $missingClass (${e.javaClass.simpleName})", e)
            } else {
                Log.e(TAG, "Failed to load extension $scraperId (linkage error): ${e.message}", e)
            }
            emptyList()
        }
    }

    /**
     * Get a cached MainAPI for the given scraper ID, loading if necessary.
     */
    fun getApi(scraperId: String): MainAPI? {
        return apiCache[scraperId] ?: run {
            val apis = loadExtension(scraperId)
            apis.firstOrNull()
        }
    }

    /**
     * Load extension with diagnostic output.
     */
    fun loadExtensionWithDiagnostics(scraperId: String, diagnostics: TestDiagnostics): List<MainAPI> {
        apiCache[scraperId]?.let {
            diagnostics.addStep("MainAPI cached: ${it.name}")
            return listOf(it)
        }

        val dexFile = File(extensionsDir, "${safeFileName(scraperId)}.cs3")
        if (!dexFile.exists()) {
            diagnostics.addStep("DEX file NOT FOUND: ${dexFile.name}")
            return emptyList()
        }
        diagnostics.addStep("DEX: ${dexFile.length()} bytes")

        // Ensure read-only
        ensureDexReadOnly(dexFile)

        return try {
            val classLoader = DexClassLoader(
                dexFile.absolutePath,
                codeCacheDir.absolutePath,
                null,
                context.classLoader
            )
            classLoaderCache[scraperId] = classLoader

            val allClasses: List<String>
            try {
                @Suppress("DEPRECATION")
                val inspectDex = dalvik.system.DexFile(dexFile)
                allClasses = inspectDex.entries().toList()
                inspectDex.close()
            } catch (e: Exception) {
                diagnostics.addStep("DEX inspection failed: ${e.message?.take(100)}")
                return emptyList()
            }

            val shadowsPlugin = allClasses.any { it == "com.lagradost.cloudstream3.plugins.Plugin" }
            if (shadowsPlugin) {
                diagnostics.addStep("WARNING: DEX contains its own Plugin class!")
            }

            val plugin = findAndLoadPlugin(classLoader, dexFile)
            if (plugin == null) {
                diagnostics.addStep("No @CloudstreamPlugin found in DEX")
                return emptyList()
            }

            val sameClass = plugin.javaClass.superclass == Plugin::class.java
            diagnostics.addStep("Plugin: ${plugin.javaClass.simpleName}, sameBaseClass=$sameClass, isWrapper=${plugin is ReflectivePluginWrapper}")

            AcraApplication.context = context
            extractorRegistry.installGlobal()

            val activity = AcraApplication.getActivity()
            try {
                plugin.load((activity as Context?) ?: context)
                diagnostics.addStep("load(Context): OK, ${plugin.registeredMainAPIs.size} APIs")
            } catch (e: Exception) {
                diagnostics.addStep("load() FAILED: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            } catch (e: Error) {
                val missing = extractMissingClassName(e)
                diagnostics.addStep("load() ERROR: ${missing ?: e.message?.take(120)}")
            }

            extractorRegistry.registerAll(plugin.registeredExtractorAPIs)

            var apis = plugin.registeredMainAPIs

            if (apis.isEmpty() || plugin.registeredExtractorAPIs.isEmpty()) {
                diagnostics.addStep("Fallback: scanning DEX for MainAPI + ExtractorApi subclasses...")
                val fallbackApis = mutableListOf<MainAPI>()
                val fallbackExtractors = mutableListOf<ExtractorApi>()
                val candidates = allClasses.filter { className ->
                    !className.contains('$') &&
                    !className.contains("Plugin") &&
                    !className.contains("Fragment") &&
                    className.startsWith("com.")
                }

                for (className in candidates) {
                    try {
                        val clazz = classLoader.loadClass(className)
                        if (apis.isEmpty()
                            && MainAPI::class.java.isAssignableFrom(clazz)
                            && !java.lang.reflect.Modifier.isAbstract(clazz.modifiers)
                            && !clazz.isInterface) {
                            val instance = clazz.getDeclaredConstructor().newInstance() as MainAPI
                            fallbackApis.add(instance)
                            diagnostics.addStep("Found API: ${instance.name} (${clazz.simpleName})")
                        } else if (ExtractorApi::class.java.isAssignableFrom(clazz)
                            && !java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) {
                            val instance = clazz.getDeclaredConstructor().newInstance() as ExtractorApi
                            fallbackExtractors.add(instance)
                            diagnostics.addStep("Found Extractor: ${instance.name} (${instance.mainUrl})")
                        }
                    } catch (e: Error) {
                        val missing = extractMissingClassName(e)
                        if (missing != null) {
                            diagnostics.addStep("${className.substringAfterLast('.')}: MISSING $missing")
                        }
                    } catch (e: Exception) {
                        val cause = e.cause ?: e
                        if (cause is Error) {
                            val missing = extractMissingClassName(cause as Error)
                            if (missing != null) {
                                diagnostics.addStep("${className.substringAfterLast('.')}: MISSING $missing")
                            }
                        }
                    }
                }

                if (fallbackApis.isNotEmpty()) {
                    diagnostics.addStep("Fallback found ${fallbackApis.size} APIs")
                    apis = fallbackApis
                }
                if (fallbackExtractors.isNotEmpty()) {
                    diagnostics.addStep("Fallback found ${fallbackExtractors.size} extractors")
                    extractorRegistry.registerAll(fallbackExtractors)
                }
            }

            apis.forEach { api ->
                apiCache["$scraperId:${api.name}"] = api
            }
            if (apis.isNotEmpty()) {
                apiCache[scraperId] = apis.first()
            }

            apis
        } catch (e: Exception) {
            diagnostics.addStep("FAILED: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            emptyList()
        } catch (e: Error) {
            val missing = extractMissingClassName(e)
            diagnostics.addStep("FAILED: ${missing ?: e.message?.take(200)}")
            emptyList()
        }
    }

    /**
     * Eagerly load all ExtractorApi subclasses from the given .cs3 files.
     */
    fun ensureExtractorsLoaded(scraperIds: List<String>, diagnostics: TestDiagnostics? = null) {
        val idsToLoad = scraperIds.filter { it !in extractorPreloadedIds }
        if (idsToLoad.isEmpty()) {
            diagnostics?.addStep("Extractors: all ${scraperIds.size} already preloaded")
            return
        }

        AcraApplication.context = context
        extractorRegistry.installGlobal()

        var totalExtractors = 0
        var totalScanned = 0

        for (scraperId in idsToLoad) {
            extractorPreloadedIds.add(scraperId)

            val dexFile = File(extensionsDir, "${safeFileName(scraperId)}.cs3")
            if (!dexFile.exists()) continue

            // Ensure read-only
            ensureDexReadOnly(dexFile)

            totalScanned++
            try {
                val classLoader = classLoaderCache.getOrPut(scraperId) {
                    DexClassLoader(
                        dexFile.absolutePath,
                        codeCacheDir.absolutePath,
                        null,
                        context.classLoader
                    )
                }

                val plugin = findAndLoadPlugin(classLoader, dexFile)
                if (plugin != null) {
                    val activity = AcraApplication.getActivity()
                    try {
                        plugin.load((activity as Context?) ?: context)
                    } catch (_: Exception) {
                    } catch (_: Error) {
                    }
                    if (plugin.registeredExtractorAPIs.isNotEmpty()) {
                        extractorRegistry.registerAll(plugin.registeredExtractorAPIs)
                        totalExtractors += plugin.registeredExtractorAPIs.size
                        continue
                    }
                }

                @Suppress("DEPRECATION")
                val inspectDex = dalvik.system.DexFile(dexFile)
                val allClasses = inspectDex.entries().toList()
                inspectDex.close()

                for (className in allClasses) {
                    if (className.contains('$')) continue
                    try {
                        val clazz = classLoader.loadClass(className)
                        if (ExtractorApi::class.java.isAssignableFrom(clazz)
                            && !java.lang.reflect.Modifier.isAbstract(clazz.modifiers)
                        ) {
                            val instance = clazz.getDeclaredConstructor().newInstance() as ExtractorApi
                            extractorRegistry.registerExtractor(instance)
                            totalExtractors++
                        }
                    } catch (_: Exception) {
                    } catch (_: Error) {
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ensureExtractorsLoaded: failed for $scraperId: ${e.message}")
            } catch (e: Error) {
                Log.w(TAG, "ensureExtractorsLoaded: linkage error for $scraperId: ${e.message}")
            }
        }

        Log.d(TAG, "ensureExtractorsLoaded: scanned $totalScanned .cs3 files, registered $totalExtractors extractors")
        diagnostics?.addStep("Preloaded $totalExtractors extractors from $totalScanned .cs3 files")
    }

    fun deleteExtension(scraperId: String) {
        apiCache.keys.filter { it.startsWith(scraperId) }.forEach { apiCache.remove(it) }
        classLoaderCache.remove(scraperId)
        extractorPreloadedIds.remove(scraperId)
        File(extensionsDir, "${safeFileName(scraperId)}.cs3").delete()
        Log.d(TAG, "Deleted extension $scraperId")
    }

    fun evictCache(scraperId: String) {
        apiCache.keys.filter { it.startsWith(scraperId) }.forEach { apiCache.remove(it) }
        classLoaderCache.remove(scraperId)
        extractorPreloadedIds.remove(scraperId)
    }

    /**
     * Ensure a DEX file is read-only. Required for Android API 28+ which blocks
     * writable DEX file loading.
     */
    private fun ensureDexReadOnly(dexFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dexFile.canWrite()) {
            dexFile.setReadOnly()
            Log.d(TAG, "Fixed DEX permissions (set read-only): ${dexFile.name}")
        }
    }

    private fun extractMissingClassName(e: Error): String? {
        val msg = e.message ?: return null
        val match = Regex("""(?:L?)([\w/.]+)(?:;)?""").find(msg)
        return match?.groupValues?.get(1)?.replace('/', '.')
    }

    private fun findAndLoadPlugin(classLoader: DexClassLoader, cs3File: File): Plugin? {
        val pluginClassName = readPluginClassNameFromZip(cs3File)
        if (pluginClassName != null) {
            try {
                Log.d(TAG, "Loading plugin class from manifest: $pluginClassName")
                val clazz = classLoader.loadClass(pluginClassName)
                val instance = clazz.getDeclaredConstructor().newInstance()
                if (instance is Plugin) {
                    return instance
                }
                if (looksLikePlugin(instance)) {
                    Log.d(TAG, "Using reflective wrapper for $pluginClassName (non-standard base class)")
                    return ReflectivePluginWrapper(instance)
                }
                Log.w(TAG, "Class $pluginClassName is not a Plugin and has no plugin methods")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load manifest class $pluginClassName: ${e.message}", e)
            } catch (e: Error) {
                Log.e(TAG, "Linkage error loading manifest class $pluginClassName: ${e.message}", e)
            }
        }

        return scanForPluginClass(classLoader, cs3File)
    }

    private fun readPluginClassNameFromZip(cs3File: File): String? {
        return try {
            ZipFile(cs3File).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json") ?: return null
                val json = zip.getInputStream(manifestEntry).bufferedReader().readText()
                val obj = JSONObject(json)
                obj.optString("pluginClassName", null)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not read manifest.json from ZIP: ${e.message}")
            null
        }
    }

    private fun scanForPluginClass(classLoader: DexClassLoader, cs3File: File): Plugin? {
        try {
            @Suppress("DEPRECATION")
            val dex = dalvik.system.DexFile(cs3File)
            val entries = dex.entries()

            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                try {
                    val clazz = classLoader.loadClass(className)
                    if (clazz.isAnnotationPresent(CloudstreamPlugin::class.java)) {
                        Log.d(TAG, "Found plugin class via scan: $className")
                        val instance = clazz.getDeclaredConstructor().newInstance()
                        if (instance is Plugin) {
                            dex.close()
                            return instance
                        }
                        if (looksLikePlugin(instance)) {
                            Log.d(TAG, "Using reflective wrapper for $className (non-standard base class)")
                            dex.close()
                            return ReflectivePluginWrapper(instance)
                        }
                        Log.w(TAG, "Annotated class $className has no plugin methods")
                    }
                } catch (_: ClassNotFoundException) {
                } catch (_: NoClassDefFoundError) {
                } catch (e: Exception) {
                    Log.w(TAG, "Error inspecting class $className: ${e.message}")
                }
            }

            dex.close()
        } catch (e: Exception) {
            Log.d(TAG, "DexFile scan fallback failed: ${e.message}")
        }

        return null
    }
}
