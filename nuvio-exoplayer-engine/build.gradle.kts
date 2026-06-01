plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nuvio.exoplayer.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17", "-O3")
            }
        }
        ndk {
            abiFilters.addAll(setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
}

val stockMedia3 by configurations.creating

dependencies {
    // Standard stock dependencies used for compilation only (no transitive leaks)
    compileOnly(libs.media3.common)
    compileOnly(libs.media3.datasource)
    compileOnly(libs.media3.datasource.okhttp)
    compileOnly(libs.media3.exoplayer)
    compileOnly(libs.media3.exoplayer.hls)
    compileOnly(libs.media3.extractor)

    // Dependencies we fetch to strip and merge
    stockMedia3(libs.media3.common)
    stockMedia3(libs.media3.datasource)
    stockMedia3(libs.media3.datasource.okhttp)
    stockMedia3(libs.media3.exoplayer)
    stockMedia3(libs.media3.exoplayer.hls)
    stockMedia3(libs.media3.extractor)

    // Explicit dependencies for annotations and utilities used in Media3
    implementation("androidx.annotation:annotation:1.9.0")
    compileOnly("com.google.errorprone:error_prone_annotations:2.26.0")
    implementation("com.google.guava:guava:33.2.1-android")
    implementation(libs.okhttp)
}

val stripAndMergeMedia3 by tasks.registering {
    val buildDirectory = layout.buildDirectory.get().asFile
    val outputJar = File(buildDirectory, "libs/stock-media3-stripped.jar")
    outputs.file(outputJar)
    
    val stockMedia3Files = stockMedia3
    inputs.files(stockMedia3Files)
    
    doLast {
        val tempDir = File(buildDirectory, "tmp/stripped-classes")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()
        
        val excludedModules = setOf(
            "media3-common",
            "media3-datasource",
            "media3-datasource-okhttp",
            "media3-exoplayer",
            "media3-exoplayer-hls",
            "media3-extractor"
        )
        
        stockMedia3Files.resolvedConfiguration.lenientConfiguration.artifacts.forEach { artifact ->
            val file = artifact.file
            val group = artifact.moduleVersion.id.group
            val moduleName = artifact.moduleVersion.id.name
            
            // Only merge and strip classes from the exact 6 excluded Media3 modules
            if (group == "androidx.media3" && moduleName in excludedModules) {
                if (file.name.endsWith(".aar")) {
                    val aarDir = File(buildDirectory, "tmp/aar-extract/${file.nameWithoutExtension}")
                    if (aarDir.exists()) aarDir.deleteRecursively()
                    copy {
                        from(zipTree(file))
                        into(aarDir)
                    }
                    val classesJar = File(aarDir, "classes.jar")
                    if (classesJar.exists()) {
                        copy {
                            from(zipTree(classesJar))
                            into(tempDir)
                        }
                    }
                } else if (file.name.endsWith(".jar")) {
                    copy {
                        from(zipTree(file))
                        into(tempDir)
                    }
                }
            }
        }
        
        // List of custom compiled optimized classes to strip from the stock jars
        val duplicates = listOf(
            "androidx/media3/common/ByteBufferDataReader",
            "androidx/media3/common/NuvioEngineConfig",
            "androidx/media3/datasource/AesCipherDataSource",
            "androidx/media3/datasource/ByteArrayDataSource",
            "androidx/media3/datasource/ContentDataSource",
            "androidx/media3/datasource/DataSchemeDataSource",
            "androidx/media3/datasource/DefaultDataSource",
            "androidx/media3/datasource/DefaultHttpDataSource",
            "androidx/media3/datasource/FileDataSource",
            "androidx/media3/datasource/FileDescriptorDataSource",
            "androidx/media3/datasource/HttpEngineDataSource",
            "androidx/media3/datasource/PriorityDataSource",
            "androidx/media3/datasource/RawResourceDataSource",
            "androidx/media3/datasource/ResolvingDataSource",
            "androidx/media3/datasource/StatsDataSource",
            "androidx/media3/datasource/TeeDataSource",
            "androidx/media3/datasource/UdpDataSource",
            "androidx/media3/datasource/cache/CacheDataSource",
            "androidx/media3/datasource/okhttp/OkHttpDataSource",
            "androidx/media3/exoplayer/hls/Aes128DataSource",
            "androidx/media3/exoplayer/source/IcyDataSource",
            "androidx/media3/exoplayer/source/SampleDataQueue",
            "androidx/media3/exoplayer/source/SampleDataQueueNative",
            "androidx/media3/exoplayer/upstream/Allocation",
            "androidx/media3/exoplayer/upstream/DefaultAllocator",
            "androidx/media3/exoplayer/upstream/DefaultAllocatorNative",
            "androidx/media3/extractor/DefaultExtractorInput",
            "androidx/media3/extractor/ForwardingExtractorInput"
        )
        
        duplicates.forEach { dup ->
            val parentPath = dup.substringBeforeLast('/')
            val className = dup.substringAfterLast('/')
            val dir = File(tempDir, parentPath)
            if (dir.exists()) {
                dir.listFiles()?.forEach { f ->
                    if (f.name == "$className.class" || f.name.startsWith("$className$")) {
                        f.delete()
                    }
                }
            }
        }
        
        // Zip the stripped classes into a single jar file
        if (!outputJar.parentFile.exists()) outputJar.parentFile.mkdirs()
        ant.withGroovyBuilder {
            "zip"("destfile" to outputJar.absolutePath, "basedir" to tempDir.absolutePath)
        }
        
        // Clean up temporary files
        File(buildDirectory, "tmp/aar-extract").deleteRecursively()
        tempDir.deleteRecursively()
    }
}

// Expose the stripped stock Media3 classes jar transitively to :app
dependencies {
    api(files(stripAndMergeMedia3.map { it.outputs.files.singleFile }))
}

