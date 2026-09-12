import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
BASH = shutil.which("bash") or ("C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else None)


@unittest.skipUnless(BASH and Path(BASH).exists(), "Bash is required for release metadata integration")
class ReleaseMetadataTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Metadata Test")
        self.git("config", "user.email", "metadata@example.invalid")
        self.version("0.0.1", 1)

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.repo, check=True, capture_output=True, text=True)

    def version(self, value, code):
        (self.repo / "version.gradle").write_text(f'versionName = "{value}"\nversionCode = {code}\n', encoding="utf-8")
        self.git("add", "version.gradle")
        self.git("commit", "-qm", "Version fixture")

    def metadata(self):
        return subprocess.run([BASH, str(ROOT / "scripts/release-metadata.sh"), "HEAD"], cwd=self.repo,
                              env={**os.environ, "VERSION_FILE": "version.gradle"}, capture_output=True, text=True)

    def test_shared_channel_contract(self):
        fixtures = json.loads((ROOT / "app/src/test/resources/updater/release-channel-cases.json").read_text())
        for index, case in enumerate(fixtures, 2):
            with self.subTest(version=case["version"]):
                self.version(case["version"].removeprefix("v"), index)
                result = self.metadata()
                self.assertEqual(0, result.returncode, result.stderr)
                fields = dict(line.split("=", 1) for line in result.stdout.splitlines())
                self.assertEqual(str(not case["stable"]).lower(), fields["prerelease"])

    def test_rejects_invalid_versions(self):
        cases = json.loads((ROOT / "app/src/test/resources/updater/release-channel-invalid.json").read_text())
        for index, version in enumerate(cases, 2):
            with self.subTest(version=version):
                self.version(version, index)
                self.assertNotEqual(0, self.metadata().returncode)

    def test_rejects_nonincreasing_code(self):
        self.version("0.0.2", 1)
        self.assertNotEqual(0, self.metadata().returncode)
