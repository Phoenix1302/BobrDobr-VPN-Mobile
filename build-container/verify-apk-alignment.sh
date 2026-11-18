#!/usr/bin/env bash
set -euo pipefail

APK_PATH="${1:-app/build/outputs/apk/debug/app-debug.apk}"

if [ ! -f "$APK_PATH" ]; then
    echo "ERROR: APK not found at $APK_PATH"
    exit 1
fi

echo "=========================================================="
echo "  Verifying APK: $APK_PATH"
echo "=========================================================="

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

echo ">>> 1. Checking for unwanted libc++_shared.so..."
if unzip -l "$APK_PATH" | grep -i "libc++_shared.so"; then
    echo "ERROR: libc++_shared.so FOUND in APK! Must be removed."
    exit 1
else
    echo "OK: libc++_shared.so is ABSENT from APK."
fi

echo ">>> 2. Extracting native libraries from APK..."
unzip -q "$APK_PATH" "lib/*" -d "$TEMP_DIR"

echo ">>> 3. Checking ELF LOAD segment alignment (target: >= 16384 / 0x4000)..."
FAILED=0
for so in $(find "$TEMP_DIR/lib" -name "*.so"); do
    rel_path="${so#$TEMP_DIR/}"
    echo "--- Checking $rel_path ---"
    
    # Check max alignment of LOAD segments
    alignments=$(readelf -lW "$so" | awk '/LOAD/ { print $NF }')
    echo "LOAD Alignments: $alignments"
    
    # Check if arm64-v8a or x86_64 has 4KB (0x1000) alignment
    if echo "$rel_path" | grep -qE "arm64-v8a|x86_64"; then
        if echo "$alignments" | grep -qw "0x1000"; then
            echo "ERROR: $rel_path has 4KB (0x1000) alignment! Android 15 requires >= 16KB (0x4000)."
            FAILED=1
        fi
    fi
done

if [ "$FAILED" -eq 0 ]; then
    echo "=========================================================="
    echo "  ALL NATIVE LIBRARIES ARE 16KB ALIGNED!                  "
    echo "=========================================================="
else
    echo "=========================================================="
    echo "  ALIGNMENT VERIFICATION FAILED!                          "
    echo "=========================================================="
    exit 1
fi
