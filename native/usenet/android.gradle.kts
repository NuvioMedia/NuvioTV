// Applied by app/build.gradle.kts. Requires Go 1.27+, Android NDK 29 and CMake.
// A PIE executable is named lib*.so solely so PackageManager installs it in the
// executable nativeLibraryDir. It is launched, never loaded through JNI.
val nativeRoot = rootProject.file("native/usenet")
val sdk = File(extra["usenetSdkDirectory"] as String)
val ndkVersion = extra["usenetNdkVersion"] as String
val ndk = File(sdk, "ndk/$ndkVersion")
val windows = System.getProperty("os.name").startsWith("Windows")
val host = if (windows) "windows-x86_64" else if (System.getProperty("os.name").startsWith("Mac")) "darwin-x86_64" else "linux-x86_64"
val suffix = if (windows) ".exe" else ""
val cmakeBin = File(sdk, "cmake/3.22.1/bin")
val toolchain = File(ndk, "toolchains/llvm/prebuilt/$host/bin")
val goBinary = providers.gradleProperty("usenetGo").orElse(providers.environmentVariable("GO_EXECUTABLE")).orElse("go")
val abis = mapOf("arm64-v8a" to Pair("arm64", "aarch64-linux-android"), "armeabi-v7a" to Pair("arm", "armv7a-linux-androideabi"), "x86_64" to Pair("amd64", "x86_64-linux-android"), "x86" to Pair("386", "i686-linux-android"))
val buildFromSource = providers.gradleProperty("buildUsenetFromSource")
    .map { it.equals("true", ignoreCase = true) || it == "1" }
    .orElse(false)
    .get() || gradle.startParameter.taskNames.any { it.contains("buildUsenet", ignoreCase = true) }

val buildTasks = abis.map { (abi, target) ->
    tasks.register("buildUsenet${abi.replace("-", "").replace("_", "")}") {
        val output = layout.buildDirectory.file("generated/usenet/jniLibs/$abi/libnuvio_usenet.so")
        val nativeBuild = layout.buildDirectory.dir("usenet/$abi")
        val prebuilt = File(nativeRoot, "prebuilt/$abi/libnuvio_usenet.so")

        if (buildFromSource || !prebuilt.exists()) {
            inputs.files(fileTree(nativeRoot) {
                include("**/*.go", "**/*.mod", "**/*.sum", "**/*.cc", "**/*.h", "**/CMakeLists.txt")
                exclude("build/**", "**/*_test.go", "prebuilt/**")
            })
            inputs.file(File(nativeRoot, "android.gradle.kts"))
            inputs.property("ndk", ndkVersion)
        } else {
            inputs.file(prebuilt)
        }
        outputs.file(output)

        doLast {
            output.get().asFile.parentFile.mkdirs()
            if (!buildFromSource && prebuilt.exists()) {
                prebuilt.copyTo(output.get().asFile, overwrite = true)
                return@doLast
            }


            val cmake = File(cmakeBin, "cmake$suffix")
            check(cmake.exists()) { "Install Android SDK CMake 3.22.1 to build Usenet" }
            check(ndk.exists()) { "Install Android NDK $ndkVersion to build Usenet" }
            project.exec {
                commandLine(cmake, "-S", File(nativeRoot, "third_party/rapidyenc-native"), "-B", nativeBuild.get().asFile,
                    "-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=${File(cmakeBin, "ninja$suffix")}",
                    "-DCMAKE_TOOLCHAIN_FILE=${File(ndk, "build/cmake/android.toolchain.cmake")}",
                    "-DANDROID_ABI=$abi", "-DANDROID_PLATFORM=android-24", "-DANDROID_STL=c++_static",
                    "-DCMAKE_BUILD_TYPE=Release", "-DDISABLE_SHARED=ON", "-DDISABLE_TOOL=ON", "-DDISABLE_CRC=ON")
            }
            project.exec { commandLine(cmake, "--build", nativeBuild.get().asFile, "--target", "rapidyenc_static", "-j", "4") }
            File(nativeBuild.get().asFile, "rapidyenc_static/librapidyenc.a").copyTo(File(nativeRoot, "third_party/rapidyenc/librapidyenc_android_${target.first}.a"), overwrite = true)
            project.exec {
                workingDir(nativeRoot)
                environment("GOOS", "android"); environment("GOARCH", target.first); environment("CGO_ENABLED", "1")
                if (target.first == "arm") environment("GOARM", "7")
                environment("CC", "\"${File(toolchain, "${target.second}24-clang${if (windows) ".cmd" else ""}").absolutePath}\"")
                environment("CXX", "\"${File(toolchain, "${target.second}24-clang++${if (windows) ".cmd" else ""}").absolutePath}\"")
                commandLine(goBinary.get(), "build", "-trimpath", "-buildvcs=false", "-buildmode=pie",
                    "-ldflags=-s -w -extldflags=-Wl,-z,max-page-size=16384", "-o", output.get().asFile, "./cmd/nuvio-usenet")
            }
            if (prebuilt.parentFile.exists() || prebuilt.parentFile.mkdirs()) {
                output.get().asFile.copyTo(prebuilt, overwrite = true)
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(buildTasks) }
