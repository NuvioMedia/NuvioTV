import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.gradle.play.publisher)
}

fun loadProperties(fileName: String): Properties = Properties().apply {
    val propertiesFile = rootProject.file(fileName)
    if (propertiesFile.exists()) {
        load(propertiesFile.inputStream())
    }
}

val localProperties = loadProperties("local.properties")

fun env(name: String): String? = providers.environmentVariable(name).orNull

fun propertyOrNull(properties: Properties, name: String): String? =
    properties.getProperty(name)?.takeIf { it.isNotBlank() }

fun releaseValue(name: String, default: String = ""): String =
    env(name) ?: propertyOrNull(localProperties, name) ?: default

fun releaseOptional(name: String): String? =
    env(name) ?: propertyOrNull(localProperties, name)

val useDebugReleaseSigning = env("CI_USE_DEBUG_SIGNING").equals("true", ignoreCase = true)
val phoneStoreFilePath = releaseOptional("OMNIO_PHONE_RELEASE_STORE_FILE")
val phoneKeyAliasValue = releaseValue("OMNIO_PHONE_RELEASE_KEY_ALIAS", "omniophone")
val phoneKeyPasswordValue = releaseValue("OMNIO_PHONE_RELEASE_KEY_PASSWORD")
val phoneStorePasswordValue = releaseValue("OMNIO_PHONE_RELEASE_STORE_PASSWORD")

android {
    namespace = "com.omnio.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.omnio.phone"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha"
    }

    signingConfigs {
        create("release") {
            keyAlias = phoneKeyAliasValue
            keyPassword = phoneKeyPasswordValue
            storeFile = phoneStoreFilePath?.let(::file) ?: file("../omnio-phone.jks")
            storePassword = phoneStorePasswordValue
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (useDebugReleaseSigning) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            applicationIdSuffix = ".debug"
            matchingFallbacks += "release"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // Keep one consistent native set across dependencies (mpv-android-lib + ass-kt
            // both ship libc++_shared.so; FFmpeg variants ship duplicate libav*.so).
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavdevice.so",
                "lib/*/libavfilter.so",
                "lib/*/libavformat.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
            optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
            optIn.add("kotlinx.coroutines.FlowPreview")
        }
    }
    buildFeatures {
        compose = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.applicationId.set("com.omnio.phone.debug")
    }
}

baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    mergeIntoMain = true
    baselineProfileOutputDir = "src/main"
    filter {
        include("com.omnio.phone.**")
    }
}

// Gradle Play Publisher (com.github.triplet.play). Listing assets and release notes
// live under app-phone/src/main/play/. Service account JSON path is provided via
// OMNIO_PHONE_PLAY_SERVICE_ACCOUNT_JSON (env or local.properties); falls back to
// rootProject/play-service-account-phone.json so local dev "just works" if the file
// is dropped in. The file is only opened when a publish task actually runs, so a
// missing path is harmless for assemble/bundle tasks.
val playServiceAccountFile = releaseOptional("OMNIO_PHONE_PLAY_SERVICE_ACCOUNT_JSON")
    ?.let(::file)
    ?: rootProject.file("play-service-account-phone.json")

play {
    serviceAccountCredentials.set(playServiceAccountFile)
    track.set("internal")
    defaultToAppBundles.set(true)
    releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.DRAFT)
    // Keep the plugin from failing the build on configuration when creds are absent;
    // the publish tasks will still error out, which is the correct behavior.
    enabled.set(playServiceAccountFile.exists())
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")

    baselineProfile(project(":baselineprofile-phone"))

    implementation(project(":core-domain"))
    implementation(project(":core-data"))
    implementation(project(":core-platform"))
    implementation(project(":core-player"))
    implementation(project(":core-ui-shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.profileinstaller)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.activity:activity-compose:1.13.0")

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // ViewModel
    implementation(libs.lifecycle.viewmodel.compose)

    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // CameraX + QR scanning + Custom Tabs (TV QR sign-in helper)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.androidx.browser)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.14.9")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
