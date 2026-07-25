from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import replace

from .consolidate import consolidate_candidates, merge_values
from .model import Candidate, Commit, GenerationResult, PullRequest
from .sources import GitHubClient, GitRepository, author_from_commit
from .text import (
    is_localization,
    localization_text,
    localized_languages,
    normalize_text,
    quality_issues,
    select_pull_request_texts,
    topic_for,
)


def generate_release_notes(
    start: str,
    end: str,
    repository: str = "",
    exclusions: tuple[str, ...] = (),
    offline: bool = False,
    path: str = ".",
) -> GenerationResult:
    git = GitRepository(path)
    commits = git.commits(start, end)
    order_by_sha = {commit.sha: commit.order for commit in commits}
    pull_requests = git.merged_pull_requests(start, end, order_by_sha)
    github = GitHubClient("" if offline else repository)
    pull_requests = github.hydrate_pull_requests(pull_requests)
    excluded_shas = resolve_exclusions(commits, exclusions)
    covered_shas = {
        sha
        for pull_request in pull_requests
        for sha in {*pull_request.commit_shas, pull_request.merge_sha}
    }
    pull_requests = [
        pull_request
        for pull_request in pull_requests
        if not should_exclude_pull_request(pull_request, excluded_shas)
        and not should_skip_pull_request(pull_request)
    ]
    direct_commits = [
        commit
        for commit in commits
        if commit.sha not in covered_shas
        and commit.sha not in excluded_shas
        and not should_skip_commit(commit)
    ]
    associated, remaining = associate_direct_commits(direct_commits, github)
    associated = [
        pull_request
        for pull_request in associated
        if not should_exclude_pull_request(pull_request, excluded_shas)
        and not should_skip_pull_request(pull_request)
    ]
    known_numbers = {pull_request.number for pull_request in pull_requests}
    for pull_request in associated:
        if pull_request.number not in known_numbers:
            pull_requests.append(pull_request)
            known_numbers.add(pull_request.number)
    associated_shas = {
        sha for pull_request in associated for sha in pull_request.commit_shas
    }
    remaining = [commit for commit in remaining if commit.sha not in associated_shas]
    direct_authors = github.commit_authors(remaining)
    candidates: list[Candidate] = []
    for pull_request in sorted(pull_requests, key=lambda item: item.order):
        candidates.extend(candidates_from_pull_request(pull_request))
    for commit in remaining:
        candidate = candidate_from_commit(
            commit,
            direct_authors.get(commit.sha),
        )
        if candidate:
            candidates.append(candidate)
    candidates = add_derived_localization(candidates)
    candidates = consolidate_candidates(candidates)
    source_counts = {
        "commits": len(commits),
        "pull_requests": len(pull_requests),
        "direct_commits": len(remaining),
        "release_notes": len(candidates),
    }
    return GenerationResult(
        candidates=candidates,
        source_counts=source_counts,
        api_failures=github.failures,
    )


def resolve_exclusions(
    commits: list[Commit],
    exclusions: tuple[str, ...],
) -> set[str]:
    resolved: set[str] = set()
    for exclusion in exclusions:
        value = exclusion.strip()
        if not value:
            continue
        matches = [commit.sha for commit in commits if commit.sha.startswith(value)]
        if len(matches) > 1:
            raise ValueError(f"Ambiguous excluded commit: {value}")
        if matches:
            resolved.add(matches[0])
    return resolved


def should_exclude_pull_request(
    pull_request: PullRequest,
    excluded_shas: set[str],
) -> bool:
    return bool(
        pull_request.merge_sha in excluded_shas
        or pull_request.commit_shas.intersection(excluded_shas)
    )


def should_skip_pull_request(pull_request: PullRequest) -> bool:
    labels = {label.lower() for label in pull_request.labels}
    if labels.intersection(
        {"no release notes", "no-release-notes", "skip release notes", "skip-release-notes"}
    ):
        return True
    return internal_only(pull_request.files) and bool(
        re.search(
            r"\b(?:ci|workflow|release automation|test|documentation|readme)\b",
            pull_request.title,
            flags=re.IGNORECASE,
        )
    )


def should_skip_commit(commit: Commit) -> bool:
    subject = commit.subject.strip()
    if re.match(r"^Merge\b", subject, flags=re.IGNORECASE):
        return True
    if re.match(
        r"^(?:chore(?:\([^)]*\))?:\s*)?bump(?:ed)?\s+(?:app\s+)?version\b",
        subject,
        flags=re.IGNORECASE,
    ):
        return True
    if subject.lower() in {"bump version", "version bump"}:
        return True
    if subject.lower() in {"cleanup", "code cleanup"}:
        return True
    if re.search(r"\bfor development\b", subject, flags=re.IGNORECASE):
        return True
    return internal_only(commit.files)


def internal_only(files: tuple[str, ...]) -> bool:
    if not files:
        return False
    internal_patterns = (
        r"^\.github/",
        r"^scripts/",
        r"^docs?/",
        r"^(?:README|CONTRIBUTING|CHANGELOG)(?:\.|$)",
        r"(?:^|/)(?:test|tests|androidTest)/",
        r"\.(?:md|txt)$",
    )
    return all(
        any(re.search(pattern, path, flags=re.IGNORECASE) for pattern in internal_patterns)
        for path in files
    )


def associate_direct_commits(
    commits: list[Commit],
    github: GitHubClient,
) -> tuple[list[PullRequest], list[Commit]]:
    if not github.available or not commits:
        return [], commits
    results: dict[str, PullRequest | None] = {}
    with ThreadPoolExecutor(max_workers=min(8, len(commits))) as executor:
        futures = {
            executor.submit(github.associated_pull_request, commit): commit
            for commit in commits
        }
        for future in as_completed(futures):
            commit = futures[future]
            results[commit.sha] = future.result()
    by_number: dict[int, PullRequest] = {}
    remaining: list[Commit] = []
    for commit in commits:
        pull_request = results.get(commit.sha)
        if not pull_request:
            remaining.append(commit)
            continue
        existing = by_number.get(pull_request.number)
        if not existing:
            by_number[pull_request.number] = pull_request
            continue
        by_number[pull_request.number] = replace(
            existing,
            order=min(existing.order, pull_request.order),
            commit_shas=existing.commit_shas.union(pull_request.commit_shas),
            files=tuple(dict.fromkeys([*existing.files, *pull_request.files])),
        )
    return sorted(by_number.values(), key=lambda item: item.order), remaining


def candidates_from_pull_request(pull_request: PullRequest) -> list[Candidate]:
    texts = select_pull_request_texts(pull_request.title, pull_request.body)
    languages = localized_languages(pull_request.files)
    locale_paths = {
        path
        for path in pull_request.files
        if re.search(r"/values-[^/]+/strings\.xml$", path)
    }
    has_non_locale_files = bool(set(pull_request.files).difference(locale_paths))
    candidates: list[Candidate] = []
    for index, source_text in enumerate(texts):
        normalized = normalize_text(source_text)
        if not normalized:
            continue
        localization = is_localization(
            normalized,
            pull_request.files,
            pull_request.labels,
        )
        textual_localization = is_localization(
            normalized,
            (),
            pull_request.labels,
        )
        if localization and languages and not has_non_locale_files:
            normalized = localization_text(languages, normalized)
        category = (
            "Localization"
            if textual_localization or localization and not has_non_locale_files
            else "Improvements & Fixes"
        )
        source = f"PR #{pull_request.number}"
        blocking, advisory = quality_issues(normalized, source)
        candidates.append(
            Candidate(
                text=normalized,
                authors=(normalize_author(pull_request.author),),
                category=category,
                topic=(
                    localization_topic(languages)
                    if category == "Localization"
                    else topic_for(normalized)
                ),
                order=pull_request.order * 10 + index,
                source=source,
                blocking_issues=blocking,
                advisory_issues=advisory,
            )
        )
    if languages and has_non_locale_files:
        main_text = " ".join(candidate.text for candidate in candidates)
        if not re.search(
            r"\b(?:translations?|locali[sz](?:ation|ed|e)|language strings?)\b",
            main_text,
            flags=re.IGNORECASE,
        ):
            text = localization_text(languages, pull_request.title)
            source = f"PR #{pull_request.number} localization"
            blocking, advisory = quality_issues(text, source)
            candidates.append(
                Candidate(
                    text=text,
                    authors=(normalize_author(pull_request.author),),
                    category="Localization",
                    topic=localization_topic(languages),
                    order=pull_request.order * 10 + len(texts),
                    source=source,
                    blocking_issues=blocking,
                    advisory_issues=advisory,
                )
            )
    return candidates


def candidate_from_commit(
    commit: Commit,
    resolved_author: str | None = None,
) -> Candidate | None:
    normalized = normalize_text(commit.subject)
    if not normalized:
        return None
    languages = localized_languages(commit.files)
    localization = is_localization(normalized, commit.files, ())
    if localization and languages:
        normalized = localization_text(languages, normalized)
    category = "Localization" if localization else "Improvements & Fixes"
    source = f"commit {commit.sha[:8]}"
    blocking, advisory = quality_issues(normalized, source)
    return Candidate(
        text=normalized,
        authors=(
            normalize_author(resolved_author or author_from_commit(commit)),
        ),
        category=category,
        topic=localization_topic(languages) if localization else topic_for(normalized),
        order=commit.order * 10,
        source=source,
        blocking_issues=blocking,
        advisory_issues=advisory,
    )


def normalize_author(author: str) -> str:
    value = author.strip().lstrip("@")
    return value or "contributor"


def localization_topic(languages: tuple[str, ...]) -> str:
    if not languages:
        return "localization"
    return "localization:" + ",".join(language.casefold() for language in languages)


def add_derived_localization(candidates: list[Candidate]) -> list[Candidate]:
    rtl_candidates = [
        candidate
        for candidate in candidates
        if candidate.category == "Improvements & Fixes"
        and candidate.topic == "rtl-subtitles"
    ]
    combined = " ".join(candidate.text for candidate in rtl_candidates)
    if not (
        re.search(r"\bRTL\b", combined)
        and re.search(r"\bHebrew\b", combined, flags=re.IGNORECASE)
    ):
        return candidates
    text = "Updated RTL subtitle localization handling for Hebrew and related languages"
    sources = ", ".join(
        dict.fromkeys(candidate.source for candidate in rtl_candidates)
    )
    blocking, advisory = quality_issues(text, sources)
    derived = Candidate(
        text=text,
        authors=merge_values(*(candidate.authors for candidate in rtl_candidates)),
        category="Localization",
        topic="localization:rtl",
        order=max(candidate.order for candidate in rtl_candidates) + 1,
        source=sources,
        blocking_issues=blocking,
        advisory_issues=advisory,
    )
    return [*candidates, derived]
