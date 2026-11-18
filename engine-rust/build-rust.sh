#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

echo ">>> Building BobrDobr VPN Rust Engine (libengine_rust.so) with 16KB page-size alignment..."

mkdir -p src/main/jniLibs

# 16 KB page size linker flags for Android 15 compatibility (LLD max-page-size and common-page-size)
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"

cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -o src/main/jniLibs \
  build --release

echo ">>> Rust build complete! Generated libraries:"
ls -lh src/main/jniLibs/*/libengine_rust.so
