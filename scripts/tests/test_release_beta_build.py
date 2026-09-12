from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import release_beta


class ReleaseBetaBuildTests(unittest.TestCase):
    def test_builds_only_full_flavor_and_returns_exact_abi_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            for name in release_beta.EXPECTED_ASSET_NAMES:
                (output / name).write_bytes(b"fixture")
            (output / "unrelated.apk").write_bytes(b"ignored")
            with patch.object(release_beta, "APK_DIR", output), patch.object(release_beta.subprocess, "run") as run:
                assets = release_beta.build_release()
            self.assertEqual(release_beta.EXPECTED_ASSET_NAMES, [path.name for path in assets])
            self.assertEqual([":app:testFullDebugUnitTest", ":app:testFullReleaseUnitTest", ":app:assembleFullRelease"],
                             [call.args[0][-1] for call in run.call_args_list])

    def test_missing_abi_fails_before_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(release_beta, "APK_DIR", Path(directory)), patch.object(release_beta.subprocess, "run"):
                with self.assertRaisesRegex(SystemExit, "Missing full release APK"):
                    release_beta.build_release()
