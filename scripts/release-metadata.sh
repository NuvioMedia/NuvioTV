#!/usr/bin/env bash

set -euo pipefail

version_file="${VERSION_FILE:-app/build.gradle.kts}"
target_ref="${1:-HEAD}"

if ! git cat-file -e "${target_ref}^{commit}" 2>/dev/null; then
    echo "Unknown release target: ${target_ref}" >&2
    exit 1
fi

read_version() {
    local commit="$1"
    git show "${commit}:${version_file}" \
        | sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*$/\1/p' \
        | head -n 1
}

read_version_code() {
    local commit="$1"
    git show "${commit}:${version_file}" \
        | sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*$/\1/p' \
        | head -n 1
}

current_version="$(read_version "$target_ref")"
current_version_code="$(read_version_code "$target_ref")"
current_bump=""
previous_version=""
previous_version_code=""
previous_bump=""
phase="current"

while IFS= read -r commit; do
    version="$(read_version "$commit")"
    version_code="$(read_version_code "$commit")"
    [[ -n "$version" && -n "$version_code" ]] || continue

    if [[ "$phase" == "current" ]]; then
        if [[ "$version" == "$current_version" ]]; then
            current_bump="$commit"
            continue
        fi
        previous_version="$version"
        previous_version_code="$version_code"
        previous_bump="$commit"
        phase="previous"
        continue
    fi

    if [[ "$version" == "$previous_version" ]]; then
        previous_bump="$commit"
        previous_version_code="$version_code"
        continue
    fi
    break
done < <(git log "$target_ref" --format='%H' -- "$version_file")

if [[ -z "$current_version" || -z "$current_version_code" ]]; then
    echo "Could not read versionName and versionCode from ${version_file}." >&2
    exit 1
fi

if [[ -z "$current_bump" || -z "$previous_bump" ]]; then
    echo "Could not find two distinct version bumps in ${version_file}." >&2
    exit 1
fi

if [[ ! "$current_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
    echo "Invalid release version: ${current_version}" >&2
    exit 1
fi

if (( 10#$current_version_code <= 10#$previous_version_code )); then
    echo "versionCode must increase from ${previous_version_code} to ${current_version_code}." >&2
    exit 1
fi

release_title="$current_version"
if [[ "$current_version" == *-beta ]]; then
    release_title="Beta ${current_version%-beta}"
fi
current_bump_subject="$(git log -1 --format='%s' "$current_bump")"
current_bump_subject_lower="$(printf '%s' "$current_bump_subject" | tr '[:upper:]' '[:lower:]')"
if [[ "$current_bump_subject_lower" == *hotfix* ]]; then
    release_title="${release_title} Hotfix"
fi

printf 'version=%s\n' "$current_version"
printf 'version_code=%s\n' "$current_version_code"
printf 'tag=%s\n' "$current_version"
printf 'title=%s\n' "$release_title"
printf 'release_commit=%s\n' "$(git rev-parse "${target_ref}^{commit}")"
printf 'current_bump=%s\n' "$current_bump"
printf 'previous_version=%s\n' "$previous_version"
printf 'previous_version_code=%s\n' "$previous_version_code"
printf 'previous_bump=%s\n' "$previous_bump"
