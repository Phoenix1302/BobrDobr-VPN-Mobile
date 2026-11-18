#!/usr/bin/env bash
set -euo pipefail

echo "=========================================================="
echo "  BobrDobr VPN Mobile: Building Go Engine (libengine_go.so)"
echo "  With 16KB Page Size Alignment (-Wl,-z,max-page-size=16384)"
echo "=========================================================="

ENGINE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JNILIBS_DIR="${ENGINE_DIR}/src/main/jniLibs"

ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/26.2.11394342}"
NDK_BIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin"

if [ ! -d "${NDK_BIN}" ]; then
    echo "ERROR: NDK Clang toolchain not found at: ${NDK_BIN}"
    exit 1
fi

echo ">>> Toolchain paths:"
echo "Go version:     $(go version)"
echo "NDK path:       ${ANDROID_NDK_HOME}"
echo "JNI output dir: ${JNILIBS_DIR}"

cd "${ENGINE_DIR}"

echo ">>> Resolving Go module dependencies..."
go mod tidy

# 1. Build arm64-v8a
echo ">>> [1/3] Compiling for arm64-v8a (aarch64)..."
mkdir -p "${JNILIBS_DIR}/arm64-v8a"
CGO_ENABLED=1 \
GOOS=android \
GOARCH=arm64 \
CC="${NDK_BIN}/aarch64-linux-android26-clang" \
go build -buildmode=c-shared -trimpath -ldflags="-s -w -extldflags '-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'" -o "${JNILIBS_DIR}/arm64-v8a/libengine_go.so" .
rm -f "${JNILIBS_DIR}/arm64-v8a/libengine_go.h"
echo "    -> Built: ${JNILIBS_DIR}/arm64-v8a/libengine_go.so ($(du -h "${JNILIBS_DIR}/arm64-v8a/libengine_go.so" | cut -f1))"

# 2. Build armeabi-v7a
echo ">>> [2/3] Compiling for armeabi-v7a (armv7)..."
mkdir -p "${JNILIBS_DIR}/armeabi-v7a"
CGO_ENABLED=1 \
GOOS=android \
GOARCH=arm \
GOARM=7 \
CC="${NDK_BIN}/armv7a-linux-androideabi26-clang" \
go build -buildmode=c-shared -trimpath -ldflags="-s -w -extldflags '-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'" -o "${JNILIBS_DIR}/armeabi-v7a/libengine_go.so" .
rm -f "${JNILIBS_DIR}/armeabi-v7a/libengine_go.h"
echo "    -> Built: ${JNILIBS_DIR}/armeabi-v7a/libengine_go.so ($(du -h "${JNILIBS_DIR}/armeabi-v7a/libengine_go.so" | cut -f1))"

# 3. Build x86_64
echo ">>> [3/3] Compiling for x86_64 (amd64)..."
mkdir -p "${JNILIBS_DIR}/x86_64"
CGO_ENABLED=1 \
GOOS=android \
GOARCH=amd64 \
CC="${NDK_BIN}/x86_64-linux-android26-clang" \
go build -buildmode=c-shared -trimpath -ldflags="-s -w -extldflags '-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'" -o "${JNILIBS_DIR}/x86_64/libengine_go.so" .
rm -f "${JNILIBS_DIR}/x86_64/libengine_go.h"
echo "    -> Built: ${JNILIBS_DIR}/x86_64/libengine_go.so ($(du -h "${JNILIBS_DIR}/x86_64/libengine_go.so" | cut -f1))"

echo "=========================================================="
echo "  Build verification (ELF LOAD segment alignment):"
echo "=========================================================="
for abi in arm64-v8a armeabi-v7a x86_64; do
    so_file="${JNILIBS_DIR}/${abi}/libengine_go.so"
    echo "--- ${abi} ---"
    readelf -lW "${so_file}" | grep -A1 LOAD
done

echo "=========================================================="
echo "  libengine_go.so compiled with 16KB alignment!           "
echo "=========================================================="
