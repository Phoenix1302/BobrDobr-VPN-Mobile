#!/usr/bin/env bash
set -euo pipefail

echo "=================================================="
echo "  BobrDobr VPN Mobile Client - Podman Build System "
echo "=================================================="

echo ">>> Toolchain Diagnostics:"
echo "Rust version:    $(rustc --version 2>/dev/null || echo 'not found')"
echo "Cargo-NDK:       $(cargo-ndk --version 2>/dev/null || echo 'installed')"
echo "Go version:      $(go version 2>/dev/null || echo 'not found')"
echo "Java version:    $(java -version 2>&1 | head -n 1)"
echo "Gradle version:  $(gradle --version 2>&1 | grep '^Gradle ' || echo 'wrapper-only')"
echo "Android SDK:     ${ANDROID_HOME:-/opt/android-sdk}"
echo "Android NDK:     ${ANDROID_NDK_HOME:-not set}"

cd /workspace

# Clean up stale locks from previous container runs if any
find /workspace/.gradle -name "*.lock" -delete 2>/dev/null || true

# If native Rust libraries are missing or rebuild requested, build them
if [ -f "engine-rust/build-rust.sh" ]; then
    if [ ! -f "engine-rust/src/main/jniLibs/arm64-v8a/libengine_rust.so" ] || [ "${FORCE_REBUILD_NATIVE:-0}" = "1" ]; then
        echo ">>> Building Rust native engine libraries..."
        chmod +x engine-rust/build-rust.sh
        bash engine-rust/build-rust.sh
    fi
fi

# If native Go libraries are missing or rebuild requested, build them
if [ -f "engine-go/build-go.sh" ]; then
    if [ ! -f "engine-go/src/main/jniLibs/arm64-v8a/libengine_go.so" ] || [ "${FORCE_REBUILD_NATIVE:-0}" = "1" ]; then
        echo ">>> Building Go native engine libraries..."
        chmod +x engine-go/build-go.sh
        bash engine-go/build-go.sh
    fi
fi

# If wrapper jar is missing, bootstrap it using installed Gradle
if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    echo ">>> Initializing Gradle wrapper..."
    if command -v gradle >/dev/null 2>&1; then
        gradle wrapper --gradle-version 8.8 --distribution-type bin
    fi
fi

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/.gradle}"
TARGET_TASK="${*:-assembleDebug}"

if command -v gradle >/dev/null 2>&1; then
    echo ">>> Executing with pre-installed Gradle 8.8: gradle ${TARGET_TASK}..."
    gradle ${TARGET_TASK} --no-daemon --no-parallel --stacktrace
elif [ -f "gradlew" ]; then
    chmod +x gradlew
    echo ">>> Executing: ./gradlew ${TARGET_TASK} --no-daemon --no-parallel --stacktrace"
    ./gradlew ${TARGET_TASK} --no-daemon --no-parallel --stacktrace
else
    echo "ERROR: gradle or gradlew not found in /workspace"
    exit 1
fi

    for apk in app/build/outputs/apk/release/*.apk app/build/outputs/apk/debug/*.apk; do
        if [ -f "$apk" ] && [ -f "build-container/verify-apk-alignment.sh" ]; then
            echo ">>> Found generated APK: $apk"
            chmod +x build-container/verify-apk-alignment.sh
            bash build-container/verify-apk-alignment.sh "$apk"

            # If named BobrDobr-mobile-*.apk, also provide app-release.apk copy for backward compatibility
            if [[ "$apk" == *"/release/BobrDobr-mobile-"* ]]; then
                cp -f "$apk" "app/build/outputs/apk/release/app-release.apk" 2>/dev/null || true
            fi
            break
        fi
    done

echo "=================================================="
echo "  Build process completed successfully!           "
echo "=================================================="
