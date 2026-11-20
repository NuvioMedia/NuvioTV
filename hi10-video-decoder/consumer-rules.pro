# JNI_OnLoad resolves these class names and registers ALL native methods, including
# debug snapshot entry points with no Java callers in Release. Preserve that ABI.
-keep,allowoptimization class com.nuvio.hi10video.NuvioHi10VideoLibrary {
    native <methods>;
}
-keep,allowoptimization class com.nuvio.hi10video.FfmpegHigh10VideoDecoder {
    native <methods>;
}
