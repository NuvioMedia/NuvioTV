from __future__ import annotations

from pathlib import Path

from .model import GenerationResult


def render_notes(result: GenerationResult) -> str:
    sections: list[str] = []
    for category in ["Improvements & Fixes", "Localization"]:
        candidates = [
            candidate
            for candidate in result.candidates
            if candidate.category == category
        ]
        if not candidates:
            continue
        lines = [f"### {category}"]
        for candidate in candidates:
            authors = ", ".join(f"@{author}" for author in candidate.authors)
            lines.append(f"- {candidate.text} ({authors})")
        sections.append("\n".join(lines))
    return "\n\n".join(sections) + ("\n" if sections else "")


def render_quality_report(result: GenerationResult) -> str:
    status = "BLOCKED" if result.blocking_issues else "PASS"
    counts = result.source_counts
    lines = [
        "# Release-note quality report",
        "",
        f"Status: **{status}**",
        "",
        "## Coverage",
        "",
        f"- Commits inspected: {counts.get('commits', 0)}",
        f"- Pull requests summarized: {counts.get('pull_requests', 0)}",
        f"- Direct commits summarized: {counts.get('direct_commits', 0)}",
        f"- Final release-note bullets: {counts.get('release_notes', 0)}",
        f"- GitHub metadata lookups failed: {result.api_failures}",
        "",
        "## Blocking issues",
        "",
    ]
    if result.blocking_issues:
        lines.extend(f"- {issue}" for issue in dict.fromkeys(result.blocking_issues))
    else:
        lines.append("- None")
    lines.extend(["", "## Advisories", ""])
    advisories = list(dict.fromkeys(result.advisory_issues))
    if advisories:
        lines.extend(f"- {issue}" for issue in dict.fromkeys(advisories))
    else:
        lines.append("- None")
    return "\n".join(lines) + "\n"


def write_quality_report(path: str, result: GenerationResult) -> None:
    Path(path).write_text(render_quality_report(result), encoding="utf-8")
