# Nuvio Usenet engine dependency

The Go engine is maintained in https://github.com/NuvioMedia/nuvio-usenet-engine.
This directory contains only the Android adapter, pinned release metadata,
download verification and license notices. No Go engine source is embedded.

Normal app builds download the exact four ABI assets named by
`prebuilt/manifest.json` and verify their SHA-256 checksums. Cached assets work
offline. The APK packages the executable as `libnuvio_usenet.so`; protocol 1,
stdin bootstrap and session lifecycle are unchanged.

To develop the engine, clone the engine repository separately and run:

```sh
./gradlew :app:assembleFullDebug -PbuildUsenetFromSource=true -PusenetEngineSourceDir=/absolute/path/to/nuvio-usenet-engine
```

Source builds require Go, NDK and CMake as documented in the engine repository.
They do not replace the pinned release cache. To update the dependency, publish
and verify a new engine release, copy its manifest here, update the source
revision in `engine-source.json`, and refresh the license notices.

Source and release hosting both use `NuvioMedia/nuvio-usenet-engine`.
Publish and verify assets there before updating the app pin; a source-only
repository copy does not copy GitHub Release assets.

The integration was carried over from `haaihond/NuvioTV:full-nntp` at
`671f1b1d76d6d19333892ed339dad3c9f0a66320`, onto official `dev`.
Original contributors include Mika Philipsen, kernexshadow and Ramon; the
original development and review history remains in PR #4426.
