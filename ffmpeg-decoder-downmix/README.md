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

## Typical workflow

1. Build and test normally with the prebuilt AAR.
2. Switch on `USE_LOCAL_FFMPEG_DECODER` only when working on native decoder changes.
3. Rebuild the patched AAR after validating local decoder changes with `:ffmpeg-decoder-downmix:assembleRelease`.
4. Replace `app/libs/lib-decoder-ffmpeg-release.aar` with `ffmpeg-decoder-downmix/build/outputs/aar/ffmpeg-decoder-downmix-release.aar`.

## VC-1 / WMV software decode

The Java/JNI video renderer lives in this module (`ExperimentalFfmpegVideoRenderer`).
The current bundled FFmpeg tree does **not** enable `vc1`/`wmv3` (`CONFIG_VC1_DECODER 0`).
Rebuild native FFmpeg before expecting picture on VC-1 remuxes:

```
src/main/jni/build_ffmpeg.sh \
  <ffmpeg-decoder-downmix> <ndk> linux-x86_64 24 \
  aac ac3 dca eac3 flac mp3 opus truehd vorbis h264 hevc \
  vc1 wmv3 wmv1 wmv2
```

Then `USE_LOCAL_FFMPEG_DECODER=true` with `FFMPEG_SOURCE_DIR` / `FFMPEG_BUILD_DIR`,
`:ffmpeg-decoder-downmix:assembleRelease`, and replace
`app/libs/lib-decoder-ffmpeg-release.aar`.
