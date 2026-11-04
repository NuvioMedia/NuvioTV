# FFmpeg Decoder Downmix

This repo supports two build modes for the patched FFmpeg decoder:

## Default mode

The app uses the patched prebuilt AAR in `app/libs/lib-decoder-ffmpeg-release.aar`.

This is the default. No local FFmpeg source tree is required.

## Local development mode

Use the source module `:ffmpeg-decoder-downmix` only when you need to rebuild or modify the patched decoder.

Enable it with one of:

- Gradle property: `-PuseLocalFfmpegDecoder=true`
- Environment variable: `USE_LOCAL_FFMPEG_DECODER=true`
- `local.properties`: `USE_LOCAL_FFMPEG_DECODER=true`

When local development mode is enabled, you must also provide:

- `FFMPEG_SOURCE_DIR`
- `FFMPEG_BUILD_DIR`

These can be set in `local.properties` or environment variables. See `local.example.properties`.

## Video decoding

Besides audio, the module decodes VC-1 and high bit depth H.264 (Hi10P, High 4:2:2 / 4:4:4)
video in software (`FfmpegVideoDecoder`, `ExperimentalFfmpegVideoRenderer`,
`src/main/jni/ffmpeg_video_jni.cc`). Media3's `DefaultRenderersFactory` loads
`ExperimentalFfmpegVideoRenderer` by name, and the app adds it explicitly when extension
renderers are off. `FfmpegVideoFormats` decides which tracks the renderer takes: VC-1, and H.264
only in profiles 110/122/244/44, so 8-bit H.264 stays on the hardware decoder. Frames reach the
surface as 8-bit YV12; 10-bit and 4:2:2 / 4:4:4 pictures are dithered down to that natively.

The renderer only claims codecs compiled into FFmpeg, so the FFmpeg build must keep `vc1` and
`h264` in its decoder list:

```
build_ffmpeg.sh <module path> <NDK path> linux-x86_64 24 \
    vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd vc1 wmv3 h264
```

and point `FFMPEG_SOURCE_DIR` at the FFmpeg tree and `FFMPEG_BUILD_DIR` at its `android-libs`.

## Typical workflow

1. Build and test normally with the prebuilt AAR.
2. Switch on `USE_LOCAL_FFMPEG_DECODER` only when working on native decoder changes.
3. Rebuild the patched AAR after validating local decoder changes with `:ffmpeg-decoder-downmix:assembleRelease`.
4. Replace `app/libs/lib-decoder-ffmpeg-release.aar` with `ffmpeg-decoder-downmix/build/outputs/aar/ffmpeg-decoder-downmix-release.aar`.
