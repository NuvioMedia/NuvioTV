from __future__ import annotations

import json
from pathlib import Path
import unittest

from release_beta import is_github_prerelease


class ReleaseChannelTests(unittest.TestCase):
    def test_zero_major_beta_is_explicitly_prerelease(self) -> None:
        self.assertTrue(is_github_prerelease("0.8.12-beta"))

    def test_shared_updater_channel_contract(self) -> None:
        fixture = Path(__file__).resolve().parents[2] / "app/src/test/resources/updater/release-channel-cases.json"
        for case in json.loads(fixture.read_text(encoding="utf-8")):
            with self.subTest(version=case["version"]):
                self.assertEqual(not case["stable"], is_github_prerelease(case["version"]))

    def test_invalid_version_fails_closed(self) -> None:
        fixture = Path(__file__).resolve().parents[2] / "app/src/test/resources/updater/release-channel-invalid.json"
        for version in json.loads(fixture.read_text(encoding="utf-8")):
            with self.subTest(version=version), self.assertRaises(ValueError):
                is_github_prerelease(version)

    def test_stable_release_is_not_a_prerelease(self) -> None:
        self.assertFalse(is_github_prerelease("1.0.0"))

    def test_post_stable_beta_is_a_prerelease(self) -> None:
        self.assertTrue(is_github_prerelease("1.1.0-beta.1"))

    def test_release_candidate_is_a_prerelease(self) -> None:
        self.assertTrue(is_github_prerelease("v1.1.0-rc.2"))


if __name__ == "__main__":
    unittest.main()
