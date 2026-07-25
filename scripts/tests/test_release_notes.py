from __future__ import annotations

import unittest
from unittest.mock import Mock, patch

from release_notes.compose import generate_release_notes, should_exclude_pull_request
from release_notes.consolidate import consolidate_candidates, merge_texts
from release_notes.model import Candidate, Commit, GenerationResult, PullRequest
from release_notes.render import render_notes
from release_notes.text import (
    is_generic,
    localized_languages,
    normalize_text,
    numbered_summary_items,
    quality_issues,
    select_pull_request_texts,
)


class ReleaseNoteTextTests(unittest.TestCase):
    def test_numbered_summary_becomes_multiple_items(self) -> None:
        body = """
## Summary
1. **Shimmer focus loss** - Restores focus after loading
2. **Grid key crash** - Prevents duplicate item keys

## Checklist
- [x] Tested
"""
        self.assertEqual(
            numbered_summary_items(body),
            [
                "Shimmer focus loss: Restores focus after loading",
                "Grid key crash: Prevents duplicate item keys",
            ],
        )

    def test_generic_title_uses_preamble(self) -> None:
        body = """
Fix trailer RTL layout and remaining-time alignment

## Summary
Updates the overlay.
"""
        self.assertEqual(
            select_pull_request_texts("Update SharedTrailerOverlay.kt", body),
            ["Fix trailer RTL layout and remaining-time alignment"],
        )

    def test_specific_title_wins_over_verbose_summary(self) -> None:
        body = """
## Summary
This changes many implementation details and contains a long explanation.
"""
        self.assertEqual(
            select_pull_request_texts(
                "Fix exact episode release times",
                body,
            ),
            ["Fix exact episode release times"],
        )

    def test_explicit_release_note_overrides_title(self) -> None:
        body = """
## Summary
Implementation detail.

## Release note
- Improved playback startup reliability
"""
        self.assertEqual(
            select_pull_request_texts("Refactor startup state", body),
            ["Improved playback startup reliability"],
        )

    def test_normalization_is_user_facing(self) -> None:
        self.assertEqual(
            normalize_text("fix(player): prevent CW state loosing focus"),
            "Prevented Continue Watching state losing focus",
        )
        self.assertEqual(
            normalize_text("Hebrew Subtitles are Working!"),
            "Fixed built-in Hebrew subtitle rendering",
        )

    def test_historical_titles_stay_reader_facing(self) -> None:
        cases = {
            "fix: handle null response body in fetchManifestText": (
                "Fixed empty add-on manifest responses"
            ),
            "Fix nullability issues in response handling in AuthManager and StreamSpeedTester": (
                "Fixed null response handling in authentication and stream speed testing"
            ),
            "ref(SettingsScreen): adjust focus handling": (
                "Improved settings focus navigation"
            ),
            "fix(tv): resolve duplicate preview programs and unit test failures": (
                "Fixed duplicate Android TV preview programs"
            ),
            "fix: trakt CW Regression": (
                "Fixed Trakt regressions affecting Continue Watching and new episodes"
            ),
        }
        for source, expected in cases.items():
            with self.subTest(source=source):
                self.assertEqual(normalize_text(source), expected)

    def test_implementation_terms_block_publish(self) -> None:
        blocking, _ = quality_issues(
            "Fixed response handling in AuthManager",
            "PR #1",
        )
        self.assertTrue(blocking)

    def test_generic_titles_are_detected(self) -> None:
        self.assertTrue(is_generic("Slider"))
        self.assertTrue(is_generic("Update PlayerScreen.kt"))
        self.assertFalse(is_generic("Fix vertical fast scrolling in Classic mode"))

    def test_locale_paths_are_named(self) -> None:
        self.assertEqual(
            localized_languages(
                (
                    "app/src/main/res/values-he/strings.xml",
                    "app/src/main/res/values-zh-rTW/strings.xml",
                )
            ),
            ("Hebrew", "Traditional Chinese"),
        )


class ReleaseNoteCompositionTests(unittest.TestCase):
    def candidate(
        self,
        text: str,
        topic: str,
        order: int,
        author: str = "person",
    ) -> Candidate:
        return Candidate(
            text=text,
            authors=(author,),
            category="Improvements & Fixes",
            topic=topic,
            order=order,
            source=f"PR #{order}",
        )

    def test_same_action_group_is_concise(self) -> None:
        self.assertEqual(
            merge_texts(
                [
                    "Fixed built-in Hebrew subtitle rendering",
                    "Fixed RTL subtitle punctuation ordering",
                ]
            ),
            "Fixed built-in Hebrew subtitle rendering and RTL subtitle punctuation ordering",
        )

    def test_topic_group_combines_authors(self) -> None:
        candidates = consolidate_candidates(
            [
                self.candidate("Fixed TLS handling", "network", 1, "one"),
                self.candidate("Improved IPv4-first DNS", "network", 2, "two"),
            ]
        )
        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0].authors, ("one", "two"))

    def test_render_uses_stable_sections(self) -> None:
        result = GenerationResult(
            candidates=[
                self.candidate("Fixed playback startup", "playback", 1),
                Candidate(
                    text="Updated Hebrew translations",
                    authors=("translator",),
                    category="Localization",
                    topic="localization",
                    order=2,
                    source="PR #2",
                ),
            ],
            source_counts={},
            api_failures=0,
        )
        self.assertEqual(
            render_notes(result),
            "### Improvements & Fixes\n"
            "- Fixed playback startup (@person)\n\n"
            "### Localization\n"
            "- Updated Hebrew translations (@translator)\n",
        )

    def test_metadata_failure_blocks_publish(self) -> None:
        result = GenerationResult(
            candidates=[],
            source_counts={},
            api_failures=1,
        )
        self.assertEqual(
            result.blocking_issues,
            ["1 GitHub metadata lookup(s) failed"],
        )

    def test_excluding_pr_commit_excludes_whole_pr(self) -> None:
        pull_request = PullRequest(
            number=1,
            title="Fix playback",
            body="",
            author="person",
            labels=(),
            merge_sha="a" * 40,
            order=1,
            commit_shas={"b" * 40},
        )
        self.assertTrue(
            should_exclude_pull_request(pull_request, {"b" * 40})
        )

    def test_excluded_pr_does_not_reenter_as_direct_commit(self) -> None:
        child_sha = "b" * 40
        merge_sha = "a" * 40
        commits = [
            Commit(child_sha, "Fix playback", "Person", "", 0),
            Commit(merge_sha, "Merge pull request #1", "Person", "", 1),
        ]
        pull_request = PullRequest(
            number=1,
            title="Fix playback",
            body="",
            author="person",
            labels=(),
            merge_sha=merge_sha,
            order=1,
            commit_shas={child_sha},
        )
        repository = Mock()
        repository.commits.return_value = commits
        repository.merged_pull_requests.return_value = [pull_request]
        github = Mock()
        github.available = False
        github.failures = 0
        github.hydrate_pull_requests.return_value = [pull_request]
        github.commit_authors.return_value = {}
        with (
            patch("release_notes.compose.GitRepository", return_value=repository),
            patch("release_notes.compose.GitHubClient", return_value=github),
        ):
            result = generate_release_notes(
                "start",
                "end",
                exclusions=(child_sha[:8],),
            )
        self.assertEqual(result.candidates, [])


if __name__ == "__main__":
    unittest.main()
