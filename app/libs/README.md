The binary ffmpeg extension was build with following decoders:

```
ENABLED_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd vc1 wmv3 h264)
```

`vc1` (with `wmv3`, which shares its code) backs the software VC-1 video decoder
(`ExperimentalFfmpegVideoRenderer`), used on devices without a working hardware VC-1 decoder.
`h264` backs software decoding of high bit depth H.264 (Hi10P, High 4:2:2 / 4:4:4), which TV
hardware decoders reject; 8-bit 4:2:0 H.264 is never claimed and stays on MediaCodec.
The current AAR was built from FFmpeg 7.0.3 with NDK 29.0.14206865 (`ANDROID_ABI=24`) via
`ffmpeg-decoder-downmix` (see its README).

Complete [build instructions](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md).

To assemble ``.aar``:

```
./gradlew :extension-ffmpeg:bundleReleaseAar
```

## AV1 AAR: dav1d instead of libgav1

`lib-decoder-av1-release.aar` SHA-256:
`6d1d44eb871918a22e370c9625e29d4661fe818a28e26a61eee1554486a4df34`.

It is the media3 1.11.1 AV1 module (`Libdav1dVideoRenderer`, dav1d) taken from Just Player's
`app/libs/lib-decoder-av1-release.aar` (commit aa85148, SHA-256
`0110fb5d50431a4269ebc21c4295bd53abfe11f0158e563368a4a46150999d94`), with one class rebuilt so it
links against the media3 1.8.0 libraries used here. Every `libdav1dJNI.so`, the manifest,
`proguard.txt` and the other classes are byte-identical to that AAR; the other classes match a
compile of the media3 1.11.1 sources. The old AAR had the libgav1 module from media3 1.8.0.

`av1-aar-src/Dav1dDecoder.java` is media3 1.11.1 `Dav1dDecoder.java` without the decoder callback
(`Decoder.Callback`, added in media3 1.11.0, not in 1.8.0; it only wakes the player early and is
never set by the 1.8.0 renderer). It is kept for reference and is not compiled into the app.

Rebuild recipe (JDK 17): compile `av1-aar-src/Dav1dDecoder.java` together with the other media3
1.11.1 `decoder_av1` sources using `--release 8 -g` against {android.jar API 35, lib-common
classes.jar, lib-exoplayer classes.jar, media3-decoder 1.8.0, androidx-annotation, guava,
checker-qual}; put the new `Dav1dDecoder.class` into the source AAR's `classes.jar` and zip it back
into the AAR, keeping entry order and timestamps.

The native library is media3's JNI wrapper (Apache 2.0), dav1d (BSD 2-Clause) and cpu_features
(Apache 2.0). It needs no other shared library and its arm64 LOAD segments are 16 KB aligned.
