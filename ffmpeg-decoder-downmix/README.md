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
