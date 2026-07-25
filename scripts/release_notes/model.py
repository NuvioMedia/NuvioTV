from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(slots=True)
class Commit:
    sha: str
    subject: str
    author_name: str
    author_email: str
    order: int
    files: tuple[str, ...] = ()


@dataclass(slots=True)
class PullRequest:
    number: int
    title: str
    body: str
    author: str
    labels: tuple[str, ...]
    merge_sha: str
    order: int
    commit_shas: set[str] = field(default_factory=set)
    files: tuple[str, ...] = ()


@dataclass(slots=True)
class Candidate:
    text: str
    authors: tuple[str, ...]
    category: str
    topic: str
    order: int
    source: str
    blocking_issues: tuple[str, ...] = ()
    advisory_issues: tuple[str, ...] = ()


@dataclass(slots=True)
class GenerationResult:
    candidates: list[Candidate]
    source_counts: dict[str, int]
    api_failures: int

    @property
    def blocking_issues(self) -> list[str]:
        issues = [
            issue
            for candidate in self.candidates
            for issue in candidate.blocking_issues
        ]
        if self.api_failures:
            issues.append(
                f"{self.api_failures} GitHub metadata lookup(s) failed"
            )
        return issues

    @property
    def advisory_issues(self) -> list[str]:
        return [
            issue
            for candidate in self.candidates
            for issue in candidate.advisory_issues
        ]
