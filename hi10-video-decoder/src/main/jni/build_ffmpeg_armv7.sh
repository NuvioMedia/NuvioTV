#!/usr/bin/env bash
set -euo pipefail

MODULE_DIR="${1:?module directory is required}"
NDK_DIR="${2:?Android NDK directory is required}"
FFMPEG_VERSION="7.0.2"
FFMPEG_COMMIT="e3a61e91030696348b56361bdf80ea358aef4a19"
FFMPEG_ARCHIVE="ffmpeg-${FFMPEG_VERSION}.tar.xz"
FFMPEG_URL="https://ffmpeg.org/releases/${FFMPEG_ARCHIVE}"
FFMPEG_SHA256="8646515b638a3ad303e23af6a3587734447cb8fc0a0c064ecdb8e95c4fd8b389"
NATIVE_DIR="${MODULE_DIR}/.native"
SOURCE_DIR="${NATIVE_DIR}/ffmpeg-${FFMPEG_VERSION}"
BUILD_DIR="${NATIVE_DIR}/ffmpeg-build/armeabi-v7a"
ARCHIVE_PATH="${NATIVE_DIR}/${FFMPEG_ARCHIVE}"
if [[ -d "${NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]; then
  TOOLCHAIN="${NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64/bin"
  HOST_CC="gcc"
else
  TOOLCHAIN="${NDK_DIR}/toolchains/llvm/prebuilt/windows-x86_64/bin"
  HOST_CC="${TOOLCHAIN}/clang"
fi

mkdir -p "${NATIVE_DIR}"
if [[ ! -f "${ARCHIVE_PATH}" ]]; then
  curl --fail --location "${FFMPEG_URL}" --output "${ARCHIVE_PATH}"
fi
echo "${FFMPEG_SHA256}  ${ARCHIVE_PATH}" | sha256sum --check

if [[ ! -d "${SOURCE_DIR}" ]]; then
  tar -xf "${ARCHIVE_PATH}" -C "${NATIVE_DIR}"
fi

rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
cd "${SOURCE_DIR}"
make distclean >/dev/null 2>&1 || true

./configure \
  --prefix="${BUILD_DIR}" \
  --target-os=android \
  --arch=arm \
  --cpu=armv7-a \
  --enable-cross-compile \
  --host-cc="${HOST_CC}" \
  --cc="${TOOLCHAIN}/armv7a-linux-androideabi24-clang" \
  --cxx="${TOOLCHAIN}/armv7a-linux-androideabi24-clang++" \
  --ar="${TOOLCHAIN}/llvm-ar" \
  --nm="${TOOLCHAIN}/llvm-nm" \
  --ranlib="${TOOLCHAIN}/llvm-ranlib" \
  --strip="${TOOLCHAIN}/llvm-strip" \
  --enable-static \
  --disable-shared \
  --enable-pic \
  --disable-everything \
  --enable-avcodec \
  --enable-avutil \
  --enable-swscale \
  --enable-decoder=h264 \
  --enable-parser=h264 \
  --disable-avdevice \
  --disable-avfilter \
  --disable-avformat \
  --disable-network \
  --disable-programs \
  --disable-doc \
  --disable-debug \
  --disable-autodetect \
  --disable-vulkan \
  --disable-v4l2-m2m \
  --extra-cflags="-O3 -fPIC -fvisibility=hidden -march=armv7-a -mfloat-abi=softfp" \
  --extra-cxxflags="-O3 -fPIC -fvisibility=hidden -fvisibility-inlines-hidden -march=armv7-a -mfloat-abi=softfp"

make -j"$(nproc)"
make install
printf 'FFmpeg release=%s commit=%s archive_sha256=%s\n' \
  "${FFMPEG_VERSION}" "${FFMPEG_COMMIT}" "${FFMPEG_SHA256}"
