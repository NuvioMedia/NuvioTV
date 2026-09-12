#!/usr/bin/env bash
set -euo pipefail
if [[ "$(uname -s)" != Linux ]]; then
    echo 'This release CLI is Linux-only. On Windows use scripts/verify-windows.ps1 with ReleaseBuild/ReleaseTest/AndroidTest.' >&2
    exit 1
fi
# Public GitHub Ubuntu runners currently have 16 GB RAM. Derive the budget from
# actual available memory, reserving room for the OS, native tools and test JVM.
task="${1:?Pass one fully qualified Gradle task}"
available_mib="$(awk '/MemAvailable:/ {print int($2 / 1024)}' /proc/meminfo)"
heap_mib=$(( available_mib - 4096 ))
(( heap_mib > 10240 )) && heap_mib=10240
if (( heap_mib < 4096 )); then
    echo "Release verification needs at least 8 GiB available RAM; found ${available_mib} MiB." >&2
    exit 1
fi
echo "Running ${task} separately: ${heap_mib} MiB heap, one worker, in-process Kotlin."
./gradlew "$task" --no-daemon --no-parallel --configure-on-demand --max-workers=1 \
    --console=plain --stacktrace \
    "-Dorg.gradle.jvmargs=-Xmx${heap_mib}m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8" \
    -Pkotlin.compiler.execution.strategy=in-process
