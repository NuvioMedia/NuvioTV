import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

fun loadProperties(fileName: String): Properties = Properties().apply {
    val propertiesFile = rootProject.file(fileName)
    if (propertiesFile.exists()) {
        load(propertiesFile.inputStream())
    }
}

val localProperties = loadProperties("local.properties")
val devProperties = loadProperties("local.dev.properties")

fun env(name: String): String? = providers.environmentVariable(name).orNull

fun propertyOrNull(properties: Properties, name: String): String? =
    properties.getProperty(name)?.takeIf { it.isNotBlank() }

fun releaseValue(name: String, default: String = ""): String =
    env(name) ?: propertyOrNull(localProperties, name) ?: default

fun debugValue(name: String, default: String = "", allowReleaseFallback: Boolean = false): String {
    val devEnvName = "DEV_$name"
    return env(devEnvName)
        ?: propertyOrNull(devProperties, name)
        ?: if (allowReleaseFallback) releaseValue(name, default) else default
}

// Mirrored from :app-tv defaultConfig.versionName so library code that needs to
// stamp requests with the app version (NetworkModule's User-Agent, etc.) works
// without an :app-tv dependency. Keep in sync with app-tv/build.gradle.kts.
val appVersionName = "0.5.26-beta"

android {
    namespace = "com.omnio.tv.core.platform"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        buildConfigField("String", "VERSION_NAME", "\"$appVersionName\"")

        // TMDB (used by TmdbServiceImpl, TmdbMetadataService, PluginRuntime)
        buildConfigField("String", "TMDB_API_KEY", "\"${releaseValue("TMDB_API_KEY")}\"")

        // Trakt (NetworkModule reads TRAKT_CLIENT_ID for trakt-api-key header,
        // and TRAKT_API_URL for the base URL)
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${releaseValue("TRAKT_CLIENT_ID")}\"")
        buildConfigField("String", "TRAKT_API_URL", "\"${releaseValue("TRAKT_API_URL", "https://api.trakt.tv/")}\"")

        // Backends consumed by NetworkModule
        buildConfigField("String", "PARENTAL_GUIDE_API_URL", "\"${releaseValue("PARENTAL_GUIDE_API_URL")}\"")
        buildConfigField("String", "INTRODB_API_URL", "\"${releaseValue("INTRODB_API_URL")}\"")
        buildConfigField("String", "TRAILER_API_URL", "\"${releaseValue("TRAILER_API_URL")}\"")
        buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", "\"${releaseValue("IMDB_RATINGS_API_BASE_URL")}\"")
        buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", "\"${releaseValue("IMDB_TAPFRAME_API_BASE_URL")}\"")
        buildConfigField("String", "DONATIONS_BASE_URL", "\"${releaseValue("DONATIONS_BASE_URL")}\"")
        buildConfigField("String", "AIOMETADATA_BASE_URL", "\"${releaseValue("AIOMETADATA_BASE_URL", "")}\"")
    }

    buildTypes {
        debug {
            // Supabase (AuthManagerImpl + SupabaseModule)
            buildConfigField("String", "SUPABASE_URL", "\"${debugValue("SUPABASE_URL")}\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"${debugValue("SUPABASE_ANON_KEY")}\"")
            // NetworkModule
            buildConfigField("String", "PARENTAL_GUIDE_API_URL", "\"${debugValue("PARENTAL_GUIDE_API_URL")}\"")
            buildConfigField("String", "INTRODB_API_URL", "\"${debugValue("INTRODB_API_URL")}\"")
            buildConfigField("String", "TRAILER_API_URL", "\"${debugValue("TRAILER_API_URL")}\"")
            buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", "\"${debugValue("IMDB_RATINGS_API_BASE_URL")}\"")
            buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", "\"${debugValue("IMDB_TAPFRAME_API_BASE_URL")}\"")
            buildConfigField("String", "DONATIONS_BASE_URL", "\"${debugValue("DONATIONS_BASE_URL", allowReleaseFallback = true)}\"")
            buildConfigField("String", "AIOMETADATA_BASE_URL", "\"${debugValue("AIOMETADATA_BASE_URL", allowReleaseFallback = true)}\"")
        }
        release {
            buildConfigField("String", "SUPABASE_URL", "\"${releaseValue("SUPABASE_URL")}\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"${releaseValue("SUPABASE_ANON_KEY")}\"")
            buildConfigField("String", "PARENTAL_GUIDE_API_URL", "\"${releaseValue("PARENTAL_GUIDE_API_URL")}\"")
            buildConfigField("String", "INTRODB_API_URL", "\"${releaseValue("INTRODB_API_URL")}\"")
            buildConfigField("String", "TRAILER_API_URL", "\"${releaseValue("TRAILER_API_URL")}\"")
            buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", "\"${releaseValue("IMDB_RATINGS_API_BASE_URL")}\"")
            buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", "\"${releaseValue("IMDB_TAPFRAME_API_BASE_URL")}\"")
            buildConfigField("String", "DONATIONS_BASE_URL", "\"${releaseValue("DONATIONS_BASE_URL")}\"")
            buildConfigField("String", "AIOMETADATA_BASE_URL", "\"${releaseValue("AIOMETADATA_BASE_URL", "")}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
            optIn.add("kotlinx.coroutines.FlowPreview")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }
}

dependencies {
    api(project(":core-domain"))
    api(project(":core-data"))

    // Hilt (DI annotations + @Module aggregations live here)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Networking
    api(libs.retrofit)
    api(libs.retrofit.moshi)
    api(libs.okhttp)
    implementation(libs.okhttp.logging)
    api(libs.moshi)
    ksp(libs.moshi.codegen)

    // Coroutines
    api(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // DataStore (consumed by sync services / preferences-backed managers)
    api(libs.datastore.preferences)

    // Supabase (AuthManagerImpl, SupabaseModule, sync services)
    api(platform(libs.supabase.bom))
    api(libs.supabase.auth)
    api(libs.supabase.postgrest)
    implementation(libs.ktor.client.okhttp)

    // Kotlinx Serialization (Supabase + DTOs in core-domain)
    api(libs.kotlinx.serialization.json)

    // Gson (PluginRuntime persists plugin state via Gson)
    implementation(libs.gson)

    // QuickJS plugin runtime + crypto-js bundle + jsoup HTML parsing
    api(libs.quickjs.kt)
    implementation(libs.jsoup)
    implementation(libs.crypto.js)
    implementation("org.webjars.npm:crypto-js:4.2.0")

    // QR code generation (QrCodeGenerator)
    implementation(libs.zxing.core)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.14.9")
}
