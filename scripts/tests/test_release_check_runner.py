import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASH = shutil.which("bash") or ("C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else None)


@unittest.skipUnless(BASH and Path(BASH).exists(), "Bash is required for runner tests")
class ReleaseRunnerTests(unittest.TestCase):
    def run_fixture(self, platform, available):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            for name, body in {"uname": f"echo {platform}", "awk": f"echo {available}", "gradlew": 'printf "%s\\n" "$@"'}.items():
                path = base / name
                path.write_text("#!/usr/bin/env bash\n" + body + "\n", newline="\n")
                path.chmod(0o755)
            return subprocess.run([BASH, "-c", 'export PATH="$(cd "$1" && pwd):$PATH"; exec bash "$2" "$3"',
                                   "fixture", str(base), str(ROOT / "scripts/run-release-check.sh"), ":app:assembleFullRelease"],
                                  cwd=base, capture_output=True, text=True)

    def test_linux_caps_heap_and_uses_one_worker(self):
        result = self.run_fixture("Linux", 15000)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("-Xmx10240m", result.stdout)
        self.assertIn("--max-workers=1", result.stdout)

    def test_small_runner_fails_before_gradle(self):
        result = self.run_fixture("Linux", 7000)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("at least 8 GiB", result.stderr)
        self.assertNotIn("--max-workers", result.stdout)

    def test_windows_directs_to_supported_powershell_verifier(self):
        result = self.run_fixture("MINGW64_NT", 50000)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("verify-windows.ps1", result.stderr)
