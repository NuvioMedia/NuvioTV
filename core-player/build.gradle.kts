plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.omnio.tv.core.player"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
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

// Globally exclude stock media3-exoplayer and media3-ui — replaced by forked local AARs in libs/
configurations.all {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-ui")
}

dependencies {
    api(project(":core-domain"))
    api(project(":core-data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)

    // Hilt (PlayerViewModel uses @HiltViewModel)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Moshi (some controllers serialize/deserialize)
    implementation(libs.moshi)

    // OkHttp (PlayerMediaSourceFactory configures HTTP client)
    implementation(libs.okhttp)

    // Forked Media3 AARs — replace stock media3-exoplayer / media3-ui (excluded above)
    api(fileTree(mapOf("dir" to "../app-tv/libs", "include" to listOf("lib-*.aar"))))
    api(files("../app-tv/libs/nextlib-mediainfo-local.aar"))

    // Media3 modules used by the engine — exposed transitively to :app-tv via api()
    api(libs.media3.exoplayer.hls)
    api(libs.media3.exoplayer.dash)
    api(libs.media3.exoplayer.smoothstreaming)
    api(libs.media3.exoplayer.rtsp)
    api(libs.media3.datasource)
    api(libs.media3.datasource.okhttp)
    api(libs.media3.decoder)
    api(libs.media3.session)
    api(libs.media3.common)
    api(libs.media3.container)
    api(libs.media3.extractor)

    // libass-android for ASS/SSA subtitle support
    api("io.github.peerless2012:ass-media:0.4.0")

    // mpv-android engine
    api("io.github.abdallahmehiz:mpv-android-lib:0.1.12")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.14.9")
}
