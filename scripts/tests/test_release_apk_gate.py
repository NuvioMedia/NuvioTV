import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASH = shutil.which("bash") or ("C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else None)


@unittest.skipUnless(BASH and Path(BASH).exists(), "Bash is required for APK gate tests")
class ReleaseApkGateTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name)
        self.tools = self.base / "build-tools/35.0.0"
        self.tools.mkdir(parents=True)
        self.apks = self.base / "apks"
        self.apks.mkdir()
        for abi in ("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal"):
            (self.apks / f"app-full-{abi}-release.apk").write_bytes(b"fixture")
        self.tool("apksigner", 'echo "Signer #1 certificate SHA-256 digest: $TEST_CERT"')
        self.tool("aapt", '''echo "package: name='${TEST_PACKAGE}' versionCode='1073' versionName='0.9.0-brusus.14'"
apk="${@: -1}"
abi="${apk##*/app-full-}"
abi="${abi%-release.apk}"
[[ "$abi" != universal ]] || abi="arm64-v8a armeabi-v7a x86 x86_64"
echo "native-code: ${TEST_ABI:-$abi}"
[[ "$TEST_DEBUGGABLE" != true ]] || echo application-debuggable
''')
        self.tool("zipalign", "exit 0")
        self.env = {**os.environ, "ANDROID_HOME": self.base.as_posix(),
                    "TEST_CERT": (ROOT / "scripts/release-signing-cert.sha256").read_text().strip(),
                    "TEST_PACKAGE": "com.nuvio.tv.brusus", "TEST_DEBUGGABLE": "false"}

    def tool(self, name, body):
        path = self.tools / name
        path.write_text("#!/usr/bin/env bash\n" + body + "\n", encoding="utf-8", newline="\n")
        path.chmod(0o755)

    def verify(self):
        return subprocess.run([BASH, "scripts/verify-release-apks.sh", "0.9.0-brusus.14", "1073", self.apks.as_posix()],
                              cwd=ROOT, env=self.env, capture_output=True, text=True)

    def test_accepts_complete_matching_release(self):
        result = self.verify()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_audit_or_other_certificate(self):
        self.env["TEST_CERT"] = "0" * 64
        self.assertNotEqual(0, self.verify().returncode)

    def test_rejects_wrong_package(self):
        self.env["TEST_PACKAGE"] = "com.nuviodebug.com"
        self.assertNotEqual(0, self.verify().returncode)

    def test_rejects_mislabeled_native_abi(self):
        self.env["TEST_ABI"] = "x86"
        self.assertNotEqual(0, self.verify().returncode)

    def test_rejects_debuggable_apk(self):
        self.env["TEST_DEBUGGABLE"] = "true"
        self.assertNotEqual(0, self.verify().returncode)

    def test_rejects_missing_abi(self):
        (self.apks / "app-full-x86-release.apk").unlink()
        self.assertNotEqual(0, self.verify().returncode)
