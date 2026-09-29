from __future__ import annotations

import unittest

from release_beta import is_github_prerelease


class ReleaseChannelTests(unittest.TestCase):
    def test_zero_major_beta_remains_visible_to_legacy_updater(self) -> None:
        self.assertFalse(is_github_prerelease("0.8.12-beta"))

    def test_stable_release_is_not_a_prerelease(self) -> None:
        self.assertFalse(is_github_prerelease("1.0.0"))

    def test_post_stable_beta_is_a_prerelease(self) -> None:
        self.assertTrue(is_github_prerelease("1.1.0-beta.1"))

    def test_release_candidate_is_a_prerelease(self) -> None:
        self.assertTrue(is_github_prerelease("v1.1.0-rc.2"))


if __name__ == "__main__":
    unittest.main()
