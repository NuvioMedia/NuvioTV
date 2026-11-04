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
