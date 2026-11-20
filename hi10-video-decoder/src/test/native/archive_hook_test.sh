#!/usr/bin/env bash
# Host regression test: archive hook must not rename callers or change code.
set -euo pipefail
JNI_DIR="$(cd "$(dirname "$0")/../../main/jni" && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "${TEST_DIR}"' EXIT
cd "${TEST_DIR}"
printf 'int ff_h264qpel_init(void) { return 42; }\n' | cc -x c -c -o h264qpel.o -
printf 'extern int ff_h264qpel_init(void); int caller(void) { return ff_h264qpel_init(); }\n' | cc -x c -c -o caller.o -
ar rcs original.a h264qpel.o caller.o
ORIGINAL_SHA="$(sha256sum original.a)"
cmake -DINPUT_ARCHIVE="${TEST_DIR}/original.a" -DOUTPUT_ARCHIVE="${TEST_DIR}/hooked.a" \
  -DAR="$(command -v ar)" -DOBJCOPY="$(command -v objcopy)" -DNM="$(command -v nm)" \
  -P "${JNI_DIR}/prepare_qpel_archive.cmake"
test "${ORIGINAL_SHA}" = "$(sha256sum original.a)"
printf 'extern int nuvio_baseline_h264qpel_init(void); extern int caller(void); int ff_h264qpel_init(void) { return nuvio_baseline_h264qpel_init()+1; } int main(void) { return caller()!=43; }\n' | cc -x c -c -o main.o -
cc main.o hooked.a -o test_hook
./test_hook
# Previously prepared archives must work too, without double wrapping.
cmake -DINPUT_ARCHIVE="${TEST_DIR}/hooked.a" -DOUTPUT_ARCHIVE="${TEST_DIR}/again.a" \
  -DAR="$(command -v ar)" -DOBJCOPY="$(command -v objcopy)" -DNM="$(command -v nm)" \
  -P "${JNI_DIR}/prepare_qpel_archive.cmake"
cc main.o again.a -o test_again
./test_again
# Reject archives missing the initializer rather than silently shipping scalar.
ar rcs invalid.a caller.o
if cmake -DINPUT_ARCHIVE="${TEST_DIR}/invalid.a" -DOUTPUT_ARCHIVE="${TEST_DIR}/invalid-output.a" \
  -DAR="$(command -v ar)" -DOBJCOPY="$(command -v objcopy)" -DNM="$(command -v nm)" \
  -P "${JNI_DIR}/prepare_qpel_archive.cmake" > invalid.log 2>&1; then
  echo 'FAIL: missing initializer accepted' >&2
  exit 1
fi
echo 'PASS: original untouched; caller reaches wrapper; idempotent; invalid archive rejected'
