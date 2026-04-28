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
// stamp requests with the app version (TraktScrobbleService) works without an
// :app-tv dependency. Keep in sync with app-tv/build.gradle.kts.
val appVersionName = "0.5.26-beta"

android {
    namespace = "com.omnio.tv.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        buildConfigField("String", "VERSION_NAME", "\"$appVersionName\"")

        // Trakt OAuth (used by TraktAuthService)
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${releaseValue("TRAKT_CLIENT_ID")}\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"${releaseValue("TRAKT_CLIENT_SECRET")}\"")
        buildConfigField("String", "TRAKT_REDIRECT_URI", "\"${releaseValue("TRAKT_REDIRECT_URI", "urn:ietf:wg:oauth:2.0:oob")}\"")

        // Backends consumed by repositories
        buildConfigField("String", "PARENTAL_GUIDE_API_URL", "\"${releaseValue("PARENTAL_GUIDE_API_URL")}\"")
        buildConfigField("String", "INTRODB_API_URL", "\"${releaseValue("INTRODB_API_URL")}\"")
        buildConfigField("String", "AVATAR_PUBLIC_BASE_URL", "\"${releaseValue("AVATAR_PUBLIC_BASE_URL")}\"")
        buildConfigField("String", "AIOMETADATA_BASE_URL", "\"${releaseValue("AIOMETADATA_BASE_URL", "")}\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "AVATAR_PUBLIC_BASE_URL", "\"${debugValue("AVATAR_PUBLIC_BASE_URL", allowReleaseFallback = true)}\"")
            buildConfigField("String", "AIOMETADATA_BASE_URL", "\"${debugValue("AIOMETADATA_BASE_URL", allowReleaseFallback = true)}\"")
            buildConfigField("String", "PARENTAL_GUIDE_API_URL", "\"${debugValue("PARENTAL_GUIDE_API_URL")}\"")
            buildConfigField("String", "INTRODB_API_URL", "\"${debugValue("INTRODB_API_URL")}\"")
        }
        release {
            buildConfigField("String", "AVATAR_PUBLIC_BASE_URL", "\"${releaseValue("AVATAR_PUBLIC_BASE_URL")}\"")
            buildConfigField("String", "AIOMETADATA_BASE_URL", "\"${releaseValue("AIOMETADATA_BASE_URL", "")}\"")
            buildConfigField("String", "PARENTAL_GUIDE_API_URL", "\"${releaseValue("PARENTAL_GUIDE_API_URL")}\"")
            buildConfigField("String", "INTRODB_API_URL", "\"${releaseValue("INTRODB_API_URL")}\"")
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

    // Hilt (DI annotations on @Inject constructors)
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

    // DataStore (DataStore types appear in public constructor params)
    api(libs.datastore.preferences)

    // Supabase
    api(platform(libs.supabase.bom))
    api(libs.supabase.auth)
    api(libs.supabase.postgrest)
    implementation(libs.ktor.client.okhttp)

    // Kotlinx Serialization (Supabase models)
    api(libs.kotlinx.serialization.json)

    // Gson (legacy persistence helpers in data/local/*)
    implementation(libs.gson)

    // Media3 — InAppYouTubeExtractor wraps DefaultHttpDataSource for the trailer engine.
    // Stock media3-exoplayer/media3-ui are still excluded globally in :app-tv so the
    // forked AARs win in the final APK; here we only need the lightweight datasource +
    // common API surface to compile the wrapper class.
    implementation(libs.media3.datasource)
    implementation(libs.media3.common)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.14.9")
}
