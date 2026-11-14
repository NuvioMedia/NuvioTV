import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "native/usenet/scripts"))
from check_prebuilts import ABIS, source_digest
from fetch_prebuilts import fetch


class PrebuiltDownloadTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "prebuilt").mkdir()
        (self.root / "engine.go").write_text("package engine\n")
        self.payload = b"verified native binary"
        self.manifest = {
            "version": 1,
            "sourceSha256": source_digest(self.root),
            "binarySha256": {abi: hashlib.sha256(self.payload).hexdigest() for abi in ABIS},
            "downloadBaseUrl": "https://github.com/example/engine/releases/download/usenet-native-1234",
        }
        self.write_manifest()
        self.requests = []

    def write_manifest(self):
        (self.root / "prebuilt/manifest.json").write_text(json.dumps(self.manifest))

    def opener(self, url, timeout):
        self.requests.append(url)
        return io.BytesIO(self.payload)

    def test_fetches_all_abis_and_reuses_verified_cache(self):
        fetch(self.root, self.opener)
        self.assertEqual(self.requests, [f"{self.manifest['downloadBaseUrl']}/libnuvio_usenet-{abi}.so" for abi in ABIS])
        fetch(self.root, lambda *a, **kw: self.fail("Valid cache must work offline"))
        for abi in ABIS:
            self.assertEqual((self.root / "prebuilt" / abi / "libnuvio_usenet.so").read_bytes(), self.payload)

    def test_repairs_only_corrupt_cached_binary(self):
        fetch(self.root, self.opener)
        self.requests.clear()
        (self.root / "prebuilt" / ABIS[0] / "libnuvio_usenet.so").write_bytes(b"corrupt")
        fetch(self.root, self.opener)
        self.assertEqual(len(self.requests), 1)

    def test_rejects_bad_download_and_removes_partial_file(self):
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            fetch(self.root, lambda *a, **kw: io.BytesIO(b"corrupt"))
        self.assertEqual(list((self.root / "prebuilt").rglob("*.so")), [])
        self.assertEqual(list((self.root / "prebuilt").rglob("*.part")), [])

    def test_network_failure_does_not_install_partial_binary(self):
        class BrokenResponse(io.BytesIO):
            def read(self, size):
                raise OSError("connection lost")
        with self.assertRaisesRegex(OSError, "connection lost"):
            fetch(self.root, lambda *a, **kw: BrokenResponse())
        self.assertEqual(list((self.root / "prebuilt").rglob("*.so")), [])
        self.assertEqual(list((self.root / "prebuilt").rglob("*.part")), [])

    def test_rejects_stale_source_before_downloading(self):
        (self.root / "engine.go").write_text("package changed\n")
        with self.assertRaisesRegex(ValueError, "different source"):
            fetch(self.root, self.opener)
        self.assertEqual(self.requests, [])

    def test_rejects_unpinned_or_insecure_url(self):
        for url in ("http://github.com/example/engine/releases/download/v1", "https://github.com/example/engine/releases/latest/download"):
            self.manifest["downloadBaseUrl"] = url
            self.write_manifest()
            with self.assertRaisesRegex(ValueError, "pin a GitHub release"):
                fetch(self.root, self.opener)
        self.assertEqual(self.requests, [])

    def test_rejects_invalid_checksum_before_downloading(self):
        self.manifest["binarySha256"][ABIS[0]] = "invalid"
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "checksums"):
            fetch(self.root, self.opener)
        self.assertEqual(self.requests, [])


if __name__ == "__main__":
    unittest.main()
