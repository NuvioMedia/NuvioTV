from __future__ import annotations

import re
from dataclasses import replace

from .model import Candidate
from .text import quality_issues


CLUSTERABLE_TOPICS = {
    "add-ons",
    "collections",
    "continue-watching",
    "diagnostics",
    "dolby-vision",
    "episode-release",
    "external-player",
    "frame-rate",
    "network",
    "profile-isolation",
    "rtl-layout",
    "rtl-subtitles",
    "subtitles",
    "trakt",
}

ACTION_WORDS = (
    "Added",
    "Adjusted",
    "Allowed",
    "Enabled",
    "Ensured",
    "Fixed",
    "Hardened",
    "Improved",
    "Made",
    "Prevented",
    "Preserved",
    "Refreshed",
    "Removed",
    "Restored",
    "Restricted",
    "Reverted",
    "Updated",
)


def consolidate_candidates(candidates: list[Candidate]) -> list[Candidate]:
    deduplicated: list[Candidate] = []
    by_text: dict[tuple[str, str], int] = {}
    for candidate in sorted(candidates, key=lambda item: item.order):
        key = (candidate.category, candidate.text.casefold())
        index = by_text.get(key)
        if index is None:
            by_text[key] = len(deduplicated)
            deduplicated.append(candidate)
            continue
        existing = deduplicated[index]
        deduplicated[index] = replace(
            existing,
            authors=merge_values(existing.authors, candidate.authors),
            source=", ".join(dict.fromkeys([existing.source, candidate.source])),
            blocking_issues=merge_values(
                existing.blocking_issues,
                candidate.blocking_issues,
            ),
            advisory_issues=merge_values(
                existing.advisory_issues,
                candidate.advisory_issues,
            ),
        )
    grouped: dict[tuple[str, str], list[Candidate]] = {}
    localization_groups: list[list[Candidate]] = []
    singles: list[Candidate] = []
    for candidate in deduplicated:
        if candidate.category == "Localization":
            candidate_languages = localization_topic_languages(candidate.topic)
            matching = [
                index
                for index, group in enumerate(localization_groups)
                if candidate_languages
                and candidate_languages.intersection(
                    set().union(
                        *(
                            localization_topic_languages(item.topic)
                            for item in group
                        )
                    )
                )
            ]
            if not matching:
                localization_groups.append([candidate])
                continue
            target = matching[0]
            localization_groups[target].append(candidate)
            for index in reversed(matching[1:]):
                localization_groups[target].extend(localization_groups.pop(index))
            continue
        key = (candidate.category, candidate.topic)
        if (
            candidate.topic not in CLUSTERABLE_TOPICS
            and candidate.category != "Localization"
        ):
            singles.append(candidate)
            continue
        grouped.setdefault(key, []).append(candidate)
    consolidated = list(singles)
    for group in localization_groups:
        merged = merge_candidate_group(group)
        consolidated.extend([merged] if merged else group)
    for group in grouped.values():
        if len(group) == 1:
            consolidated.extend(group)
            continue
        merged = merge_candidate_group(group)
        if merged:
            consolidated.append(merged)
        else:
            consolidated.extend(group)
    category_order = {"Improvements & Fixes": 0, "Localization": 1}
    return sorted(
        consolidated,
        key=lambda item: (category_order.get(item.category, 2), item.order),
    )


def localization_topic_languages(topic: str) -> set[str]:
    if not topic.startswith("localization:"):
        return set()
    return {language for language in topic.removeprefix("localization:").split(",") if language}


def merge_candidate_group(group: list[Candidate]) -> Candidate | None:
    ordered = sorted(group, key=lambda item: item.order)
    if ordered[0].category == "Localization":
        preferred = max(
            ordered,
            key=lambda item: (
                bool(re.search(r"\b(?:missing|formal|completed|synced)\b", item.text)),
                len(item.text),
            ),
        )
        sources = ", ".join(
            dict.fromkeys(candidate.source for candidate in ordered)
        )
        blocking, advisory = quality_issues(preferred.text, sources)
        return replace(
            preferred,
            authors=merge_values(*(candidate.authors for candidate in ordered)),
            order=min(candidate.order for candidate in ordered),
            source=sources,
            blocking_issues=blocking,
            advisory_issues=advisory,
        )
    combined_text = " ".join(candidate.text for candidate in ordered)
    if (
        ordered[0].topic == "diagnostics"
        and re.search(r"\b(?:playback|issue report)\b", combined_text, flags=re.IGNORECASE)
    ):
        merged_text = (
            "Added playback issue diagnostics with opt-in report controls, "
            "playback settings, and reporting stability improvements"
        )
    elif (
        ordered[0].topic == "external-player"
        and "cache-sync" in combined_text
        and "dismissible loader" in combined_text
    ):
        merged_text = (
            "Improved external player auto-next reliability with a faster "
            "cache-sync fallback, dismissible loader, and absolute-numbered anime support"
        )
    elif (
        ordered[0].topic == "external-player"
        and "backed-out next source" in combined_text
        and "finale detection" in combined_text
    ):
        merged_text = (
            "Improved external player auto-next stability with backed-out source "
            "recovery, a reliable loader, binge-group continuity, and finale detection"
        )
    elif (
        ordered[0].topic == "continue-watching"
        and "Trakt regressions" in combined_text
        and "caught-up series" in combined_text
    ):
        merged_text = (
            "Fixed Trakt regressions affecting new episodes and improved Continue "
            "Watching behavior for caught-up series when new episodes air"
        )
    elif (
        ordered[0].topic == "rtl-subtitles"
        and "Hebrew" in combined_text
        and "mirrored parentheses" in combined_text
    ):
        merged_text = (
            "Improved RTL subtitle rendering with corrected punctuation ordering, "
            "mirrored parentheses, and built-in Hebrew subtitle support"
        )
    else:
        merged_text = merge_texts([candidate.text for candidate in ordered])
    if not merged_text or len(merged_text) > 300:
        return None
    sources = ", ".join(
        dict.fromkeys(candidate.source for candidate in ordered)
    )
    blocking, advisory = quality_issues(merged_text, sources)
    return Candidate(
        text=merged_text,
        authors=merge_values(*(candidate.authors for candidate in ordered)),
        category=ordered[0].category,
        topic=ordered[0].topic,
        order=min(candidate.order for candidate in ordered),
        source=sources,
        blocking_issues=blocking,
        advisory_issues=advisory,
    )


def merge_texts(texts: list[str]) -> str:
    unique = list(dict.fromkeys(text.strip(" .") for text in texts if text.strip()))
    if len(unique) < 2:
        return unique[0] if unique else ""
    parsed = [split_action(text) for text in unique]
    if all(action and action == parsed[0][0] for action, _ in parsed):
        action = parsed[0][0]
        details = [detail for _, detail in parsed]
        return f"{action} {join_phrases(details)}"
    clauses = [unique[0]]
    for text in unique[1:]:
        clauses.append(lower_action(text))
    return join_phrases(clauses)


def split_action(text: str) -> tuple[str, str]:
    for action in ACTION_WORDS:
        prefix = f"{action} "
        if text.startswith(prefix):
            return action, text[len(prefix) :]
    return "", text


def lower_action(text: str) -> str:
    action, detail = split_action(text)
    if not action:
        return text[0].lower() + text[1:] if text else text
    return f"{action.lower()} {detail}"


def join_phrases(values: list[str]) -> str:
    if len(values) == 1:
        return values[0]
    if len(values) == 2:
        return f"{values[0]} and {values[1]}"
    return f"{', '.join(values[:-1])}, and {values[-1]}"


def merge_values(*groups: tuple[str, ...]) -> tuple[str, ...]:
    return tuple(dict.fromkeys(value for group in groups for value in group))
