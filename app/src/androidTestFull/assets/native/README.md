# Isolated native regression fixtures

`sample_iamf.mp4` is the 34,617-byte PCM IAMF test fixture from AndroidX Media3
1.8.0, path `libraries/test_data/src/test/assets/media/mp4/sample_iamf.mp4`.
Source: https://github.com/androidx/media/tree/1.8.0/libraries/test_data/src/test/assets/media/mp4
Licensed under Apache-2.0; the upstream license is included here.
The first decoded stereo-buffer hashes are from the corresponding upstream
`audiosinkdumps/mp4/sample_iamf.mp4.audiosink.dump` golden.

`localhost-test.p12` is a generated, public test-only RSA key/certificate, valid
2020–2040 for localhost/127.0.0.1. Password: `test-fixture`. It is intentionally
not a secret and must never be used outside these loopback tests. The test trusts
it only in its own SSLContext, validates the hostname, and does not alter the
application/system trust store or global security provider order.

All files belong only to the full-flavor instrumentation APK, not the app APK.
The WireGuard test loads the native backend and reads its version; it does not
request VPN permission, configure a peer, create a tunnel, or touch saved config.
