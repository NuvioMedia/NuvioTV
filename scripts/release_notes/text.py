from __future__ import annotations

import re
from collections.abc import Iterable

from .rewrites import SPECIAL_RULES
from .vocabulary import ACTION_VERBS, LOCALE_NAMES


def clean_body(body: str) -> str:
    value = re.sub(r"<!--.*?-->", "", body or "", flags=re.DOTALL)
    value = value.replace("\r\n", "\n").replace("\r", "\n")
    return "\n".join(line.rstrip() for line in value.splitlines()).strip()


def summary_section(body: str) -> str:
    value = clean_body(body)
    match = re.search(
        r"(?ims)^#{1,6}\s+summary\s*$\n(.*?)(?=^#{1,6}\s+\S|\Z)",
        value,
    )
    return match.group(1).strip() if match else ""


def body_preamble(body: str) -> str:
    value = clean_body(body)
    preamble = re.split(r"(?m)^#{1,6}\s+\S", value, maxsplit=1)[0]
    paragraphs = markdown_paragraphs(preamble)
    return paragraphs[0] if paragraphs else ""


def markdown_paragraphs(value: str) -> list[str]:
    paragraphs: list[str] = []
    current: list[str] = []
    for raw_line in value.splitlines():
        line = raw_line.strip()
        if not line:
            if current:
                paragraphs.append(" ".join(current))
                current = []
            continue
        if re.match(r"^[-*+]\s+\[[ xX]\]\s+", line):
            continue
        if line.startswith("#") or line.startswith("|"):
            continue
        if re.match(r"^[-*+]\s+", line) or re.match(r"^\d+[.)]\s+", line):
            if current:
                paragraphs.append(" ".join(current))
                current = []
            continue
        current.append(line)
    if current:
        paragraphs.append(" ".join(current))
    return [plain_markdown(paragraph) for paragraph in paragraphs if paragraph]


def numbered_summary_items(body: str) -> list[str]:
    section = summary_section(body)
    items: list[str] = []
    for line in section.splitlines():
        match = re.match(
            r"^\s*\d+[.)]\s+\*\*(.+?)\*\*(?:\s*[-:–—]\s*|\s+)?(.*)$",
            line,
        )
        if not match:
            continue
        heading = plain_markdown(match.group(1))
        detail = plain_markdown(match.group(2))
        if detail and len(detail.split()) <= 24:
            items.append(f"{heading}: {detail}")
        else:
            items.append(heading)
    return items if len(items) > 1 else []


def plain_markdown(value: str) -> str:
    text = value.strip()
    text = re.sub(r"!\[([^\]]*)\]\([^)]+\)", r"\1", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"[*_~`]+", "", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip(" \t-–—:")


def strip_conventional_prefix(value: str) -> str:
    return re.sub(
        r"^(?:feat|fix|perf|ref|refactor|chore|build|ci|docs|style|test)"
        r"(?:\([^)]+\))?!?:\s*",
        "",
        value,
        flags=re.IGNORECASE,
    ).strip()


def is_generic(value: str) -> bool:
    text = strip_conventional_prefix(plain_markdown(value)).strip()
    lower = text.lower()
    if not lower:
        return True
    if lower in {
        "changes",
        "dev",
        "fix",
        "fixes",
        "hotfix",
        "misc",
        "slider",
        "update",
        "updates",
    }:
        return True
    if re.match(r"^(?:add|create|delete|fix|update)\s+files?(?:\s+via\s+upload)?$", lower):
        return True
    if re.match(r"^(?:add|create|delete|fix|update)\s+[\w.-]+\.(?:kt|java|xml|gradle|kts)$", lower):
        return True
    if re.match(r"^(?:merge|sync)(?:\s+\S+){0,2}$", lower):
        return True
    return len(text.split()) < 3 and not re.match(
        r"^(?:added|fixed|improved|removed|restored|updated)\b",
        lower,
    )


def select_pull_request_texts(title: str, body: str) -> list[str]:
    release_note = release_note_section(body)
    if release_note:
        return release_note
    clean_title = strip_conventional_prefix(plain_markdown(title))
    numbered = numbered_summary_items(body)
    if (
        numbered
        and "," in clean_title
        and re.search(r"\band\b", clean_title, flags=re.IGNORECASE)
    ):
        return numbered
    preamble = body_preamble(body)
    summary = summary_section(body)
    summary_paragraphs = markdown_paragraphs(summary)
    if is_generic(clean_title):
        for option in [preamble, *summary_paragraphs]:
            if option and not is_generic(option):
                return [option]
    if clean_title:
        return [clean_title]
    for option in [preamble, *summary_paragraphs]:
        if option:
            return [option]
    return [title]


def release_note_section(body: str) -> list[str]:
    value = clean_body(body)
    match = re.search(
        r"(?ims)^#{1,6}\s+(?:release notes?|changelog)\s*$\n"
        r"(.*?)(?=^#{1,6}\s+\S|\Z)",
        value,
    )
    if not match:
        return []
    section = match.group(1).strip()
    lines = [
        plain_markdown(re.sub(r"^\s*(?:[-*+]|\d+[.)])\s+", "", line))
        for line in section.splitlines()
        if re.match(r"^\s*(?:[-*+]|\d+[.)])\s+", line)
    ]
    if lines:
        return [line for line in lines if line]
    return markdown_paragraphs(section)[:1]


def normalize_text(value: str) -> str:
    text = strip_conventional_prefix(plain_markdown(value))
    text = re.sub(r"\s+#\d+\s*$", "", text)
    text = re.sub(r"^\[[^\]]+\]\s*", "", text)
    text = re.sub(r"\bCW\b", "Continue Watching", text, flags=re.IGNORECASE)
    text = re.sub(r"\badd[\s-]?ons\b", "add-ons", text, flags=re.IGNORECASE)
    text = re.sub(r"\badd[\s-]?on\b", "add-on", text, flags=re.IGNORECASE)
    text = re.sub(r"\bloosing\b", "losing", text, flags=re.IGNORECASE)
    text = re.sub(r"\boccured\b", "occurred", text, flags=re.IGNORECASE)
    text = re.sub(r"\bseperate\b", "separate", text, flags=re.IGNORECASE)
    text = re.sub(r"\bsubtile\b", "subtitle", text, flags=re.IGNORECASE)
    text = re.sub(r"\btimeleft\b", "remaining time", text, flags=re.IGNORECASE)
    text = re.sub(r"\baddons?\b", "add-on", text, flags=re.IGNORECASE)
    text = re.sub(r"\bDV\s+Profile\b", "Dolby Vision Profile", text)
    text = re.sub(r"\bback\s+buffer\b", "back-buffer", text, flags=re.IGNORECASE)
    text = text.strip(" .")
    if re.fullmatch(r"Hebrew Subtitles are Working!?", text, flags=re.IGNORECASE):
        return "Fixed built-in Hebrew subtitle rendering"
    for pattern, replacement in SPECIAL_RULES:
        if re.fullmatch(pattern, text, flags=re.IGNORECASE):
            return replacement
    match = re.match(r"^([A-Za-z]+)\b(.*)$", text)
    if match:
        replacement = ACTION_VERBS.get(match.group(1).lower())
        if replacement:
            remainder = match.group(2).lstrip()
            if replacement == "Added support for":
                remainder = re.sub(
                    r"^(?:support\s+)?for\s+",
                    "",
                    remainder,
                    flags=re.IGNORECASE,
                )
            text = f"{replacement} {remainder}".strip()
    text = re.sub(r"\band\s+add\b", "and added", text, flags=re.IGNORECASE)
    text = re.sub(r"\band\s+fix\b", "and fixed", text, flags=re.IGNORECASE)
    text = re.sub(r"\band\s+remove\b", "and removed", text, flags=re.IGNORECASE)
    text = re.sub(r"\band\s+use\b", "and switched to", text, flags=re.IGNORECASE)
    text = re.sub(r"\bbehaviour\b", "behavior", text, flags=re.IGNORECASE)
    text = re.sub(r"\bself hosted\b", "self-hosted", text, flags=re.IGNORECASE)
    text = re.sub(r"\bsign out\b", "sign-out", text, flags=re.IGNORECASE)
    text = re.sub(r"\bdeeplinks?\b", "deep links", text, flags=re.IGNORECASE)
    text = re.sub(r"\bdetailscreen\b", "detail screens", text, flags=re.IGNORECASE)
    text = re.sub(r"\bSpanish \(LatAm\)", "Latin American Spanish", text)
    text = re.sub(r"\bexternal-player\b", "external player", text, flags=re.IGNORECASE)
    text = re.sub(r"\bauto next\b", "auto-next", text, flags=re.IGNORECASE)
    text = re.sub(r"\bRTL nav\b", "RTL navigation", text)
    if not re.match(
        r"^(?:Added|Adjusted|Allowed|Enabled|Ensured|Fixed|Hardened|Improved|Made|"
        r"Prevented|Preserved|Refreshed|Removed|Restored|Restricted|Reverted|Updated)\b",
        text,
    ) and re.search(
        r"\b(?:bug|crash|failure|gibberish|issue|loss|regression|reset|stuck)\b",
        text,
        flags=re.IGNORECASE,
    ):
        text = f"Fixed {text[0].lower() + text[1:]}"
    text = re.sub(r"\s+", " ", text).strip(" .")
    text = re.sub(
        r"\b(Dolby Vision Profile [578]) Handling\b",
        r"\1 handling",
        text,
    )
    if text and text[0].islower():
        text = text[0].upper() + text[1:]
    return text


def localized_languages(files: Iterable[str]) -> tuple[str, ...]:
    languages: list[str] = []
    for path in files:
        match = re.search(r"/values-([^/]+)/strings\.xml$", path)
        if not match:
            continue
        qualifier = match.group(1)
        language = LOCALE_NAMES.get(qualifier)
        if not language:
            language = LOCALE_NAMES.get(qualifier.split("-")[0])
        if language and language not in languages:
            languages.append(language)
    return tuple(languages)


def is_localization(text: str, files: Iterable[str], labels: Iterable[str]) -> bool:
    lower = text.lower()
    label_text = " ".join(labels).lower()
    return bool(
        localized_languages(files)
        or re.search(
            r"\b(?:locali[sz](?:ation|ed|e)|translations?|language strings?)\b",
            lower,
        )
        or "localization" in label_text
        or "translation" in label_text
    )


def localization_text(languages: tuple[str, ...], source_text: str) -> str:
    lower = source_text.lower()
    if not languages:
        return normalize_text(source_text)
    if len(languages) == 1:
        joined = languages[0]
    elif len(languages) == 2:
        joined = f"{languages[0]} and {languages[1]}"
    else:
        joined = f"{', '.join(languages[:-1])}, and {languages[-1]}"
    action = "Added" if re.search(r"\b(?:add|new|initial)\b", lower) else "Updated"
    noun = "translations" if len(languages) > 1 else "translations"
    return f"{action} {joined} {noun}"


def topic_for(text: str) -> str:
    lower = text.lower()
    rules = [
        ("localization", r"\b(?:translations?|locali[sz]|language strings?)\b"),
        ("rtl-subtitles", r"\b(?:rtl|hebrew).*\bsubtitle|\bsubtitle.*\b(?:rtl|hebrew)"),
        ("rtl-layout", r"\brtl\b.*\b(?:slider|trailer|progress|layout|navigation|focus|ui)\b"),
        (
            "dolby-vision",
            r"\b(?:dolby vision|dv(?:\s*profile)?\s*[578]|rpu|blockadditional)\b",
        ),
        ("frame-rate", r"\b(?:afr|frame rate|framerate|display mode|vsync|surfaceflinger)\b"),
        ("network", r"\b(?:sni|tls|ssl|dns|ipv4|okhttp|ktor|network client)\b"),
        ("external-player", r"\bexternal player\b|\bauto-next\b"),
        (
            "profile-isolation",
            r"\b(?:profiles?\b.*\b(?:isolat|leak|race|switch|sync)|"
            r"(?:isolat|leak|race|switch).*\bprofiles?)\b",
        ),
        ("continue-watching", r"\bcontinue watching\b"),
        ("episode-release", r"\b(?:episode release|airtime|upcoming episode)\b"),
        ("trakt", r"\btrakt\b"),
        ("subtitles", r"\b(?:subtitle|ass font|matroska font)\b"),
        ("collections", r"\b(?:collection|folder view)\b"),
        ("add-ons", r"\b(?:add-on|manifest)\b"),
        ("authentication", r"\b(?:auth|login|sign-out|token refresh|credential)\b"),
        (
            "diagnostics",
            r"\b(?:diagnostics?|sentry|issue reports?|issue reporting|"
            r"playback reports?|tombstone)\b",
        ),
        ("settings", r"\b(?:setting|preference|datastore)\b"),
        ("navigation", r"\b(?:focus|d-pad|navigation|drawer)\b"),
        ("playback", r"\b(?:player|playback|buffer|stream|exo|mpv|hls)\b"),
    ]
    for topic, pattern in rules:
        if re.search(pattern, lower):
            return topic
    return "general"


def quality_issues(text: str, source: str) -> tuple[tuple[str, ...], tuple[str, ...]]:
    blocking: list[str] = []
    advisory: list[str] = []
    if is_generic(text):
        blocking.append(f"{source} needs a user-facing description")
    if re.search(r"(?:^|[\s/])[\w.-]+\.(?:kt|java|xml|gradle|kts)(?:$|[\s)])", text):
        blocking.append(f"{source} still refers to an implementation filename")
    if re.search(
        r"\b(?:DataStore|LoadControl|OkHttpClient|RepositoryImpl|ViewModel)\b|"
        r"\b\w+(?:Manager|Controller|Repository|Tester)\b|"
        r"\b[a-z]+(?:[A-Z][A-Za-z0-9]*)+\b|\w+\(\)",
        text,
    ):
        blocking.append(f"{source} still contains implementation terminology")
    if text.endswith(("…", "...")):
        blocking.append(f"{source} appears to be truncated")
    if re.search(r"\b(?:NUVIO-TV-\w+|[0-9a-f]{7,40})\b", text, flags=re.IGNORECASE):
        blocking.append(f"{source} still contains an internal identifier")
    if len(text) > 300:
        blocking.append(f"{source} exceeds 300 characters")
    elif len(text) > 220:
        advisory.append(f"{source} is longer than 220 characters")
    if len(text.split()) < 4 and not re.match(
        r"^(?:Added|Updated) .+ translations$",
        text,
    ):
        advisory.append(f"{source} may be too terse")
    if not re.match(
        r"^(?:Added|Adjusted|Allowed|Enabled|Ensured|Fixed|Hardened|Improved|Made|"
        r"Prevented|Preserved|Refreshed|Removed|Restored|Restricted|Reverted|Updated)\b",
        text,
    ):
        blocking.append(f"{source} does not start with a user-facing action")
    return tuple(dict.fromkeys(blocking)), tuple(dict.fromkeys(advisory))
