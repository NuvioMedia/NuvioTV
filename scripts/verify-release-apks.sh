#!/usr/bin/env bash
set -euo pipefail

expected_version="${1:?Expected versionName is required}"
expected_code="${2:?Expected versionCode is required}"
apk_directory="${3:-app/build/outputs/apk/full/release}"
expected_certificate="$(tr -d '[:space:]' < scripts/release-signing-cert.sha256)"
build_tools="$(find "${ANDROID_HOME:?ANDROID_HOME is required}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
[[ "$expected_certificate" =~ ^[0-9a-f]{64}$ ]] || { echo 'Invalid pinned release certificate.' >&2; exit 1; }
for abi in arm64-v8a armeabi-v7a x86_64 x86 universal; do
    apk="${apk_directory}/app-full-${abi}-release.apk"
    [[ -s "$apk" ]] || { echo "Missing release APK: ${apk}" >&2; exit 1; }
    verify_output="$("${build_tools}/apksigner" verify --print-certs "$apk" 2>&1)" || {
        echo "APK signature verification failed: ${abi}" >&2
        echo "${verify_output}" >&2
        exit 1
    }
    certificate="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<< "$verify_output")"
    [[ -n "$certificate" ]] || {
        echo "Could not read the signer certificate from apksigner's output: ${abi}" >&2
        echo "${verify_output}" >&2
        exit 1
    }
    [[ "$certificate" == "$expected_certificate" ]] || {
        echo "Release signing identity mismatch: ${abi}" >&2
        echo "  expected: ${expected_certificate}" >&2
        echo "  actual:   ${certificate}" >&2
        exit 1
    }
    badging="$("${build_tools}/aapt" dump badging "$apk")"
    package_line="${badging%%$'\n'*}"
    [[ "$package_line" == *"name='com.nuvio.tv.brusus'"* && "$package_line" == *"versionCode='${expected_code}'"* && "$package_line" == *"versionName='${expected_version}'"* ]] || {
        echo "Release package/version metadata mismatch: ${abi}" >&2; exit 1;
    }
    if grep -q '^application-debuggable' <<< "$badging"; then
        echo "Release must not be debuggable: ${abi}" >&2; exit 1
    fi
    native_line="$(sed -n 's/^native-code: //p' <<< "$badging")"
    read -r -a native_abis <<< "$native_line"
    actual_abis="$(printf '%s\n' "${native_abis[@]}" | tr -d "'" | sort)"
    if [[ "$abi" == universal ]]; then
        expected_abis="$(printf '%s\n' arm64-v8a armeabi-v7a x86 x86_64 | sort)"
    else
        expected_abis="$abi"
    fi
    [[ "$actual_abis" == "$expected_abis" ]] || { echo "Release native ABI mismatch: ${abi}" >&2; exit 1; }
    "${build_tools}/zipalign" -c -P 16 4 "$apk" >/dev/null
    echo "Verified release identity, version, signing and ZIP alignment: ${abi}"
done
