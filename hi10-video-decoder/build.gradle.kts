plugins {
    id("com.android.library")
}

android {
    namespace = "com.nuvio.hi10video"
    compileSdk = 36
    ndkVersion = "27.0.12077973" // Preserve the validated JNI/MC compiler toolchain.
    buildFeatures {
        buildConfig = true // Compile-time gate for debug playback telemetry.
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += "armeabi-v7a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DFFMPEG_SOURCE_DIR=${project.layout.projectDirectory.dir(".native/ffmpeg-7.0.2").asFile.absolutePath}",
                    "-DFFMPEG_BUILD_DIR=${project.layout.projectDirectory.dir(".native/ffmpeg-build/armeabi-v7a").asFile.absolutePath}"
                )
                cppFlags += listOf("-O3", "-fvisibility=hidden", "-fvisibility-inlines-hidden")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    compileOnly(files("../app/libs/lib-common-release.aar"))
    compileOnly(libs.media3.decoder)
    compileOnly(files("../app/libs/lib-exoplayer-release.aar"))
    compileOnly("androidx.annotation:annotation:1.9.1")

    testImplementation(files("../app/libs/lib-common-release.aar"))
    testImplementation(files("../app/libs/lib-exoplayer-release.aar"))
    testImplementation(libs.media3.decoder)
    testImplementation("com.google.guava:guava:33.3.1-android")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.2")
}
