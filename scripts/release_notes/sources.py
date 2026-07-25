from __future__ import annotations

import json
import re
import shutil
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import replace

from .model import Commit, PullRequest


class GitRepository:
    def __init__(self, path: str = ".") -> None:
        self.path = path

    def run(self, *args: str) -> str:
        result = subprocess.run(
            ["git", *args],
            cwd=self.path,
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            message = result.stderr.strip() or result.stdout.strip()
            raise RuntimeError(message or f"git {' '.join(args)} failed")
        return result.stdout

    def resolve(self, ref: str) -> str:
        return self.run("rev-parse", "--verify", f"{ref}^{{commit}}").strip()

    def commits(self, start: str, end: str) -> list[Commit]:
        revision_range = f"{self.resolve(start)}..{self.resolve(end)}"
        output = self.run(
            "log",
            "--reverse",
            "--format=%H%x1f%s%x1f%an%x1f%ae",
            revision_range,
        )
        commits: list[Commit] = []
        for order, line in enumerate(output.splitlines()):
            fields = line.split("\x1f")
            if len(fields) != 4:
                continue
            sha, subject, author_name, author_email = fields
            commits.append(
                Commit(
                    sha=sha,
                    subject=subject.strip(),
                    author_name=author_name.strip(),
                    author_email=author_email.strip(),
                    order=order,
                    files=self.files_for_commit(sha),
                )
            )
        return commits

    def files_for_commit(self, sha: str) -> tuple[str, ...]:
        output = self.run(
            "diff-tree",
            "--root",
            "--no-commit-id",
            "--name-only",
            "-r",
            sha,
        )
        return tuple(line for line in output.splitlines() if line)

    def merged_pull_requests(
        self,
        start: str,
        end: str,
        order_by_sha: dict[str, int],
    ) -> list[PullRequest]:
        revision_range = f"{self.resolve(start)}..{self.resolve(end)}"
        output = self.run(
            "log",
            "--reverse",
            "--format=%H%x1f%P%x1f%s%x1f%b%x1e",
            revision_range,
        )
        pull_requests: list[PullRequest] = []
        pattern = re.compile(r"^Merge pull request #(\d+) from ([^/\s]+)(?:/.*)?$")
        for record in output.split("\x1e"):
            fields = record.strip("\n").split("\x1f", 3)
            if len(fields) != 4:
                continue
            sha, parents_text, subject, body = fields
            match = pattern.match(subject.strip())
            parents = parents_text.split()
            if not match or len(parents) < 2:
                continue
            number = int(match.group(1))
            branch_author = match.group(2)
            title = next(
                (line.strip() for line in body.splitlines() if line.strip()),
                subject.strip(),
            )
            first_parent, second_parent = parents[:2]
            commit_shas = set(
                self.run(
                    "rev-list",
                    "--no-merges",
                    f"{first_parent}..{second_parent}",
                ).splitlines()
            )
            files = tuple(
                line
                for line in self.run(
                    "diff",
                    "--name-only",
                    first_parent,
                    sha,
                ).splitlines()
                if line
            )
            pull_requests.append(
                PullRequest(
                    number=number,
                    title=title,
                    body="",
                    author=branch_author,
                    labels=(),
                    merge_sha=sha,
                    order=order_by_sha.get(sha, len(order_by_sha)),
                    commit_shas=commit_shas,
                    files=files,
                )
            )
        return pull_requests


class GitHubClient:
    def __init__(self, repository: str) -> None:
        self.repository = repository
        self.available = bool(repository and shutil.which("gh"))
        self.failures = 0 if self.available or not repository else 1

    def api(self, path: str) -> object:
        if not self.available:
            raise RuntimeError("GitHub metadata is unavailable")
        result = subprocess.run(
            ["gh", "api", f"repos/{self.repository}/{path}"],
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or f"GitHub API failed for {path}")
        return json.loads(result.stdout)

    def hydrate_pull_requests(
        self,
        pull_requests: list[PullRequest],
    ) -> list[PullRequest]:
        if not self.available or not pull_requests:
            return pull_requests
        hydrated: dict[int, PullRequest] = {}
        with ThreadPoolExecutor(max_workers=min(8, len(pull_requests))) as executor:
            futures = {
                executor.submit(self.api, f"pulls/{pull_request.number}"): pull_request
                for pull_request in pull_requests
            }
            for future in as_completed(futures):
                pull_request = futures[future]
                try:
                    payload = future.result()
                    if not isinstance(payload, dict):
                        raise RuntimeError("Unexpected pull request response")
                    user = payload.get("user") or {}
                    labels = payload.get("labels") or []
                    hydrated[pull_request.number] = replace(
                        pull_request,
                        title=str(payload.get("title") or pull_request.title).strip(),
                        body=str(payload.get("body") or ""),
                        author=str(user.get("login") or pull_request.author),
                        labels=tuple(
                            str(label.get("name"))
                            for label in labels
                            if isinstance(label, dict) and label.get("name")
                        ),
                    )
                except (RuntimeError, json.JSONDecodeError):
                    self.failures += 1
        return [
            hydrated.get(pull_request.number, pull_request)
            for pull_request in pull_requests
        ]

    def associated_pull_request(
        self,
        commit: Commit,
    ) -> PullRequest | None:
        if not self.available:
            return None
        try:
            payload = self.api(f"commits/{commit.sha}/pulls")
        except (RuntimeError, json.JSONDecodeError):
            self.failures += 1
            return None
        if not isinstance(payload, list) or not payload:
            return None
        merged = [
            item
            for item in payload
            if isinstance(item, dict) and item.get("merged_at")
        ]
        item = merged[0] if merged else payload[0]
        if not isinstance(item, dict) or not item.get("number"):
            return None
        user = item.get("user") or {}
        labels = item.get("labels") or []
        return PullRequest(
            number=int(item["number"]),
            title=str(item.get("title") or commit.subject).strip(),
            body=str(item.get("body") or ""),
            author=str(user.get("login") or author_from_commit(commit)),
            labels=tuple(
                str(label.get("name"))
                for label in labels
                if isinstance(label, dict) and label.get("name")
            ),
            merge_sha=str(item.get("merge_commit_sha") or commit.sha),
            order=commit.order,
            commit_shas={commit.sha},
            files=commit.files,
        )

    def commit_authors(self, commits: list[Commit]) -> dict[str, str]:
        if not self.available or not commits:
            return {}
        authors: dict[str, str] = {}
        with ThreadPoolExecutor(max_workers=min(8, len(commits))) as executor:
            futures = {
                executor.submit(self.api, f"commits/{commit.sha}"): commit
                for commit in commits
            }
            for future in as_completed(futures):
                commit = futures[future]
                try:
                    payload = future.result()
                    if not isinstance(payload, dict):
                        raise RuntimeError("Unexpected commit response")
                    author = payload.get("author") or {}
                    login = author.get("login") if isinstance(author, dict) else None
                    if login:
                        authors[commit.sha] = str(login)
                except (RuntimeError, json.JSONDecodeError):
                    self.failures += 1
        return authors


def author_from_commit(commit: Commit) -> str:
    noreply = re.search(r"\+([^@]+)@users\.noreply\.github\.com$", commit.author_email)
    if noreply:
        return noreply.group(1)
    simple_noreply = re.search(r"^([^@]+)@users\.noreply\.github\.com$", commit.author_email)
    if simple_noreply:
        return simple_noreply.group(1)
    normalized = re.sub(r"[^A-Za-z0-9-]+", "", commit.author_name.replace(" ", "-"))
    return normalized or "contributor"
