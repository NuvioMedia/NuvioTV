#!/usr/bin/env python3

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILD_FILE = ROOT / "app" / "build.gradle.kts"
RELEASE_OUTPUT_DIR = ROOT / "build" / "release"
APK_DIR = ROOT / "app" / "build" / "outputs" / "apk" / "release"
DEFAULT_BETA_NOTICE = (
    "## This is a beta version intended for testing only. Expect breaking changes "
    "in updates. Normal users are advised to wait for the stable release."
)
EXPECTED_ASSET_NAMES = [
    "app-arm64-v8a-release.apk",
    "app-armeabi-v7a-release.apk",
    "app-x86_64-release.apk",
    "app-x86-release.apk",
    "app-universal-release.apk",
]
VERSION_NAME_RE = re.compile(r'(?m)^(\s*versionName\s*=\s*")([^"]+)(")')
VERSION_CODE_RE = re.compile(r"(?m)^(\s*versionCode\s*=\s*)(\d+)")
VERSION_PATTERN = re.compile(r"^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$")
PREFIX_RE = re.compile(
    r"^(feat|fix|ref|refactor|perf|ui|ux|build|style|docs|test|ci|chore)"
    r"(\([^)]+\))?:\s*",
    re.IGNORECASE,
)
LEADING_ISSUE_RE = re.compile(
    r"^(fixe?[sd]?|close[sd]?|resolve[sd]?|address(?:e[sd])?)\s*#\d+\s*,?\s*",
    re.IGNORECASE,
)
DROP_PATTERNS = (
    re.compile(r"^merge\b", re.IGNORECASE),
    re.compile(r"^revert\b", re.IGNORECASE),
    re.compile(r"^docs?[:\s]", re.IGNORECASE),
    re.compile(r"^ci[:\s]", re.IGNORECASE),
    re.compile(r"^test[:\s]", re.IGNORECASE),
    re.compile(r"^chore[:\s]", re.IGNORECASE),
    re.compile(r"^bump\b", re.IGNORECASE),
    re.compile(r"^update .*translation", re.IGNORECASE),
    re.compile(r"^merge .*translation", re.IGNORECASE),
    re.compile(r"^close unlabeled issues workflow", re.IGNORECASE),
    re.compile(r"^update issue reporting title rule", re.IGNORECASE),
    re.compile(r"^merge conflict$", re.IGNORECASE),
    re.compile(r"^update .* version to ", re.IGNORECASE),
    re.compile(r"^update .*\.(kt|java|xml|gradle\.kts?)$", re.IGNORECASE),
    re.compile(r"baselineprofile", re.IGNORECASE),
)
TRIM_TOKENS = ("cw", "wip")
WORD_REPLACEMENTS = (
    (re.compile(r"\bcw\b", re.IGNORECASE), ""),
    (re.compile(r"\bhomelayouts\b", re.IGNORECASE), "Home layouts"),
    (re.compile(r"\bshowInHome param\b", re.IGNORECASE), "`showInHome` parameter"),
    (re.compile(r"\bui\b"), "UI"),
    (re.compile(r"\btrakt\b", re.IGNORECASE), "Trakt"),
    (re.compile(r"\bnuvio\b", re.IGNORECASE), "Nuvio"),
)
ASSET_ORDER = {
    "arm64-v8a": 0,
    "armeabi-v7a": 1,
    "x86_64": 2,
    "x86": 3,
    "universal": 4,
}


def run(
    *args: str,
    check: bool = True,
    capture_output: bool = True,
    cwd: Path = ROOT,
    env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=cwd,
        check=check,
        text=True,
        capture_output=capture_output,
        env=env,
    )


def git(*args: str, check: bool = True) -> str:
    return run("git", *args, check=check).stdout.strip()


def require_tool(name: str) -> None:
    if shutil.which(name) is None:
        raise SystemExit(f"Missing required tool: {name}")


def read_build_file() -> str:
    return BUILD_FILE.read_text(encoding="utf-8-sig")


def parse_versions(contents: str) -> tuple[str, int]:
    version_match = VERSION_NAME_RE.search(contents)
    code_match = VERSION_CODE_RE.search(contents)
    if version_match is None or code_match is None:
        raise SystemExit(f"Failed to find versionName/versionCode in {BUILD_FILE}")
    return version_match.group(2), int(code_match.group(2))


def updated_build_file(contents: str, version_name: str, version_code: int) -> str:
    contents, version_replacements = VERSION_NAME_RE.subn(
        rf"\g<1>{version_name}\g<3>", contents, count=1
    )
    contents, code_replacements = VERSION_CODE_RE.subn(
        rf"\g<1>{version_code}", contents, count=1
    )
    if version_replacements != 1 or code_replacements != 1:
        raise SystemExit(f"Failed to update versionName/versionCode in {BUILD_FILE}")
    return contents


def write_build_file(contents: str) -> None:
    BUILD_FILE.write_text(contents, encoding="utf-8-sig")


def last_tag() -> str | None:
    result = run("git", "describe", "--tags", "--abbrev=0", check=False)
    tag = result.stdout.strip()
    return tag or None


def release_range(previous_tag: str | None) -> str | None:
    if previous_tag:
        return f"{previous_tag}..HEAD"
    return None


def commit_subjects(previous_tag: str | None) -> list[str]:
    args = ["log", "--reverse", "--pretty=format:%s"]
    revision_range = release_range(previous_tag)
    if revision_range:
        args.append(revision_range)
    output = git(*args)
    return [line.strip() for line in output.splitlines() if line.strip()]


def normalize_subject(subject: str) -> str | None:
    if not subject:
        return None

    candidate = subject.strip()
    for pattern in DROP_PATTERNS:
        if pattern.search(candidate):
            return None

    candidate = PREFIX_RE.sub("", candidate)
    while True:
        updated = LEADING_ISSUE_RE.sub("", candidate)
        if updated == candidate:
            break
        candidate = updated

    candidate = candidate.strip(" .")
    if not candidate:
        return None

    if candidate.lower().startswith(("with ", "one more ", "trying ")):
        return None

    words = candidate.split()
    while words and words[-1].lower() in TRIM_TOKENS:
        words.pop()
    candidate = " ".join(words).strip()
    if not candidate:
        return None

    for pattern, replacement in WORD_REPLACEMENTS:
        candidate = pattern.sub(replacement, candidate)
    candidate = re.sub(r"\s{2,}", " ", candidate).strip()
    if not candidate:
        return None

    if candidate.islower():
        candidate = candidate[:1].upper() + candidate[1:]

    return candidate


def dedupe_keep_latest(items: list[str], limit: int) -> list[str]:
    deduped: list[str] = []
    seen: set[str] = set()
    for item in items:
        key = item.casefold()
        if key in seen:
            continue
        seen.add(key)
        deduped.append(item)
    if limit > 0 and len(deduped) > limit:
        deduped = deduped[-limit:]
    return deduped


def parse_extra_notes(notes_text: str | None, extra_lines: list[str]) -> list[str]:
    parsed: list[str] = []
    if notes_text:
        for raw_line in notes_text.splitlines():
            line = raw_line.strip()
            if not line:
                continue
            if line.startswith("- "):
                line = line[2:].strip()
            parsed.append(line)
    parsed.extend(line.strip() for line in extra_lines if line.strip())
    return parsed


def build_release_notes(
    previous_tag: str | None,
    max_items: int,
    downloader_code: str | None,
    extra_notes: str | None,
    extra_lines: list[str],
    custom_notes: str | None,
) -> str:
    if custom_notes and custom_notes.strip():
        return custom_notes.strip() + "\n"

    normalized = [
        note
        for note in (normalize_subject(subject) for subject in commit_subjects(previous_tag))
        if note
    ]
    bullet_items = dedupe_keep_latest(normalized, max_items)
    bullet_items.extend(parse_extra_notes(extra_notes, extra_lines))

    if not bullet_items:
        bullet_items = ["Beta maintenance update"]

    lines = [DEFAULT_BETA_NOTICE, "", "### Improvements & Fixes"]
    lines.extend(f"- {item}" for item in bullet_items)
    if downloader_code:
        lines.extend(["", f"### Downloader Code - {downloader_code.strip()}"])
    return "\n".join(lines).strip() + "\n"


def release_notes_path(version_name: str) -> Path:
    RELEASE_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    safe_name = version_name.replace("/", "-")
    return RELEASE_OUTPUT_DIR / f"release-notes-{safe_name}.md"


def write_release_notes(version_name: str, notes: str) -> Path:
    path = release_notes_path(version_name)
    path.write_text(notes, encoding="utf-8")
    return path


def append_job_summary(
    *,
    mode: str,
    version_name: str,
    release_tag: str,
    release_title: str,
    commit_message: str,
    version_code: int,
    previous_tag: str | None,
    notes_path: Path,
    assets: list[Path] | None,
) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return

    lines = [
        "## Beta Release Preview",
        "",
        f"- Mode: `{mode}`",
        f"- Version: `{version_name}`",
        f"- Tag: `{release_tag}`",
        f"- Title: `{release_title}`",
        f"- Commit message: `{commit_message}`",
        f"- versionCode: `{version_code}`",
        f"- Previous tag: `{previous_tag or 'none'}`",
        f"- Notes file: `{notes_path.relative_to(ROOT)}`",
        "",
        "### Release Notes",
        "",
        notes_path.read_text(encoding="utf-8").strip(),
        "",
        "### Assets",
    ]

    if assets:
        lines.extend(f"- `{path.name}`" for path in assets)
    else:
        lines.extend(f"- `{name}`" for name in EXPECTED_ASSET_NAMES)

    lines.append("")
    with open(summary_path, "a", encoding="utf-8") as handle:
        handle.write("\n".join(lines))


def current_branch() -> str:
    branch = git("rev-parse", "--abbrev-ref", "HEAD")
    if branch == "HEAD":
        branch = os.environ.get("GITHUB_REF_NAME", "").strip()
    if not branch:
        raise SystemExit("Unable to determine the current branch for publishing")
    return branch


def ensure_clean_worktree() -> None:
    status = git("status", "--short")
    if status:
        raise SystemExit(
            "Refusing to publish from a dirty worktree. Commit or stash changes first."
        )


def ensure_version_available(release_tag: str) -> None:
    tag_check = run(
        "git", "rev-parse", "-q", "--verify", f"refs/tags/{release_tag}", check=False
    )
    if tag_check.returncode == 0:
        raise SystemExit(f"Tag already exists: {release_tag}")


def build_release() -> list[Path]:
    subprocess.run(
        ["./gradlew", "app:assembleRelease"],
        cwd=ROOT,
        check=True,
        text=True,
    )
    assets = sorted(
        APK_DIR.glob("*.apk"),
        key=lambda path: next(
            (order for token, order in ASSET_ORDER.items() if token in path.name),
            999,
        ),
    )
    if not assets:
        raise SystemExit(f"No APK assets found in {APK_DIR}")
    return assets


def commit_tag_push(
    release_tag: str,
    release_title: str,
    commit_message: str,
    branch_name: str,
) -> None:
    subprocess.run(["git", "add", str(BUILD_FILE.relative_to(ROOT))], cwd=ROOT, check=True)
    subprocess.run(
        ["git", "commit", "-m", commit_message],
        cwd=ROOT,
        check=True,
        text=True,
    )
    subprocess.run(
        ["git", "tag", "-a", release_tag, "-m", f"Release {release_title}"],
        cwd=ROOT,
        check=True,
        text=True,
    )
    subprocess.run(["git", "push", "origin", f"HEAD:{branch_name}"], cwd=ROOT, check=True)
    subprocess.run(["git", "push", "origin", release_tag], cwd=ROOT, check=True)


def tag_push(
    release_tag: str,
    release_title: str,
    branch_name: str,
) -> None:
    subprocess.run(
        ["git", "tag", "-a", release_tag, "-m", f"Release {release_title}"],
        cwd=ROOT,
        check=True,
        text=True,
    )
    subprocess.run(["git", "push", "origin", f"HEAD:{branch_name}"], cwd=ROOT, check=True)
    subprocess.run(["git", "push", "origin", release_tag], cwd=ROOT, check=True)


def create_github_release(
    release_tag: str,
    release_title: str,
    notes_path: Path,
    assets: list[Path],
    *,
    draft: bool,
) -> None:
    require_tool("gh")
    command = [
        "gh",
        "release",
        "create",
        release_tag,
        *[str(asset) for asset in assets],
        "--title",
        release_title,
        "--notes-file",
        str(notes_path),
    ]
    if draft:
        command.append("--draft")
    else:
        command.append("--latest")
    subprocess.run(command, cwd=ROOT, check=True, text=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Preview, draft, or publish a beta GitHub release by either bumping the app "
            "version or manually supplying the release tag/title, then building APKs and "
            "generating release notes from recent commits."
        )
    )
    parser.add_argument(
        "version",
        nargs="?",
        help="Target versionName, for example 0.4.19-beta. Required unless --manual-release is set.",
    )
    parser.add_argument(
        "--manual-release",
        action="store_true",
        help="Skip changing Gradle version metadata and publish using the provided tag/title.",
    )
    parser.add_argument(
        "--version-code",
        type=int,
        help="Explicit versionCode to write. Defaults to current versionCode + 1. Ignored in manual mode.",
    )
    parser.add_argument(
        "--release-tag",
        help="Git tag to create for the release. Defaults to the target versionName.",
    )
    parser.add_argument(
        "--release-title",
        help="GitHub release title. Defaults to the release tag.",
    )
    parser.add_argument(
        "--commit-message",
        help="Git commit message for the version bump. Defaults to 'release: <release-tag>'.",
    )
    parser.add_argument(
        "--custom-notes",
        help="Full release notes markdown. When set, it replaces the generated notes entirely.",
    )
    parser.add_argument(
        "--custom-notes-file",
        help="Path to a markdown file whose contents replace the generated release notes entirely.",
    )
    parser.add_argument(
        "--downloader-code",
        help="Optional release-note footer value for the downloader code",
    )
    parser.add_argument(
        "--extra-notes",
        help="Optional multi-line text. Each non-empty line is appended as a bullet.",
    )
    parser.add_argument(
        "--extra-line",
        action="append",
        default=[],
        help="Append one extra bullet to the Improvements & Fixes list.",
    )
    parser.add_argument(
        "--max-items",
        type=int,
        default=10,
        help="Maximum number of generated commit bullets to keep.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Show the generated release notes without editing files, building, or publishing.",
    )
    parser.add_argument(
        "--publish",
        action="store_true",
        help="Build all release APKs, commit the version bump, push tag/branch, and create the GitHub release.",
    )
    parser.add_argument(
        "--draft",
        action="store_true",
        help="Build all release APKs, commit the version bump, push tag/branch, and create the GitHub release as a draft.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    selected_modes = [args.dry_run, args.publish, args.draft]
    if sum(1 for enabled in selected_modes if enabled) > 1:
        raise SystemExit("Use only one of --dry-run, --draft, or --publish.")
    if args.custom_notes and args.custom_notes_file:
        raise SystemExit("Use either --custom-notes or --custom-notes-file, not both.")

    original_contents = read_build_file()
    current_version_name, current_version_code = parse_versions(original_contents)
    previous_tag = last_tag()

    if args.manual_release:
        if args.version:
            raise SystemExit("Do not provide version when --manual-release is set.")
        if args.version_code is not None:
            raise SystemExit("--version-code is not supported when --manual-release is set.")
        if not args.release_tag:
            raise SystemExit("--release-tag is required when --manual-release is set.")
        if not args.release_title:
            raise SystemExit("--release-title is required when --manual-release is set.")
        target_version_name = current_version_name
        next_version_code = current_version_code
        release_tag = args.release_tag
        release_title = args.release_title
        commit_message = "manual release: no version bump"
        notes_key = release_tag
    else:
        if not args.version:
            raise SystemExit("version is required unless --manual-release is set.")
        if not VERSION_PATTERN.match(args.version):
            raise SystemExit(f"Invalid version format: {args.version}")
        target_version_name = args.version
        next_version_code = (
            args.version_code if args.version_code is not None else current_version_code + 1
        )
        if next_version_code < 1:
            raise SystemExit("versionCode must be a positive integer.")
        release_tag = args.release_tag or target_version_name
        release_title = args.release_title or release_tag
        commit_message = args.commit_message or f"release: {release_tag}"
        notes_key = target_version_name

    custom_notes = args.custom_notes
    if args.custom_notes_file:
        custom_notes = Path(args.custom_notes_file).read_text(encoding="utf-8")
    notes = build_release_notes(
        previous_tag=previous_tag,
        max_items=args.max_items,
        downloader_code=args.downloader_code,
        extra_notes=args.extra_notes,
        extra_lines=args.extra_line,
        custom_notes=custom_notes,
    )
    notes_path = write_release_notes(notes_key, notes)

    print(f"Current versionName: {current_version_name}")
    print(f"Current versionCode: {current_version_code}")
    print(f"Target versionName: {target_version_name}")
    print(f"Release tag: {release_tag}")
    print(f"Release title: {release_title}")
    print(f"Commit message: {commit_message}")
    print(f"Target versionCode: {next_version_code}")
    print(f"Previous tag: {previous_tag or 'none'}")
    print(f"Release notes: {notes_path.relative_to(ROOT)}")
    print()
    print(notes.strip())
    print()

    mode = (
        "dry-run" if args.dry_run
        else "draft" if args.draft
        else "publish" if args.publish
        else "local-build"
    )

    if args.dry_run:
        print("Dry run expected assets:")
        for asset_name in EXPECTED_ASSET_NAMES:
            print(f"- {asset_name}")
        append_job_summary(
            mode=mode,
            version_name=target_version_name,
            release_tag=release_tag,
            release_title=release_title,
            commit_message=commit_message,
            version_code=next_version_code,
            previous_tag=previous_tag,
            notes_path=notes_path,
            assets=None,
        )
        return 0

    if args.publish or args.draft:
        ensure_clean_worktree()
        ensure_version_available(release_tag)

    updated_contents = original_contents
    if not args.manual_release:
        updated_contents = updated_build_file(
            original_contents,
            version_name=target_version_name,
            version_code=next_version_code,
        )
        write_build_file(updated_contents)
        print(f"Updated {BUILD_FILE.relative_to(ROOT)}")

    assets: list[Path] = []
    try:
        assets = build_release()
        print("Built release assets:")
        for asset in assets:
            print(f"- {asset.relative_to(ROOT)}")

        if args.publish or args.draft:
            branch_name = current_branch()
            if args.manual_release:
                tag_push(release_tag, release_title, branch_name)
            else:
                commit_tag_push(
                    release_tag, release_title, commit_message, branch_name
                )
            create_github_release(
                release_tag,
                release_title,
                notes_path,
                assets,
                draft=args.draft,
            )
            if args.draft:
                print(
                    f"Created draft GitHub release {release_tag} "
                    f"({release_title}) from branch {branch_name}"
                )
            else:
                print(
                    f"Published GitHub release {release_tag} "
                    f"({release_title}) from branch {branch_name}"
                )
    except Exception:
        if not (args.publish or args.draft):
            write_build_file(original_contents)
        raise

    append_job_summary(
        mode=mode,
        version_name=target_version_name,
        release_tag=release_tag,
        release_title=release_title,
        commit_message=commit_message,
        version_code=next_version_code,
        previous_tag=previous_tag,
        notes_path=notes_path,
        assets=assets,
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as exc:
        if exc.stdout:
            sys.stdout.write(exc.stdout)
        if exc.stderr:
            sys.stderr.write(exc.stderr)
        raise SystemExit(exc.returncode) from exc
