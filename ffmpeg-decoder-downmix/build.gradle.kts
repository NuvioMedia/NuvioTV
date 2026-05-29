import java.util.Properties

plugins {
    id("com.android.library")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

fun localPath(name: String): String? {
    return providers.environmentVariable(name).orNull
        ?: localProperties.getProperty(name)
}

val ffmpegSourceDir = localPath("FFMPEG_SOURCE_DIR")
val ffmpegBuildDir = localPath("FFMPEG_BUILD_DIR")

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        externalNativeBuild {
            cmake {
                if (!ffmpegSourceDir.isNullOrBlank() && !ffmpegBuildDir.isNullOrBlank()) {
                    arguments += listOf(
                        "-DFFMPEG_SOURCE_DIR=$ffmpegSourceDir",
                        "-DFFMPEG_BUILD_DIR=$ffmpegBuildDir"
                    )
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = false
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    api(libs.media3.decoder)
    implementation(libs.media3.common)
    compileOnly(files("../app/libs/lib-exoplayer-release.aar"))
    implementation("androidx.annotation:annotation:1.9.1")
    compileOnly("org.checkerframework:checker-qual:3.48.4")
}
