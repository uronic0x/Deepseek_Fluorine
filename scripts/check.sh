#!/bin/sh
set -eu
cd "$(dirname "$0")/.."

echo "==> [1/4] Web unit tests & syntax checks"
if command -v node >/dev/null 2>&1; then
    node --check web/core.js
    node --check web/app.js
    node --test tests/core.test.mjs
else
    echo "ERROR: node is required for web tests" >&2
    exit 1
fi

echo "==> [2/4] Rust fmt / test / build / clippy"
if command -v cargo >/dev/null 2>&1; then
    cargo fmt --check
    cargo test
    cargo build
    cargo clippy -- -D warnings
else
    echo "SKIP: cargo not available in current environment" >&2
fi

echo "==> [3/4] Desktop integration tests"
if command -v cargo >/dev/null 2>&1 && command -v python3 >/dev/null 2>&1; then
    python3 tests/integration.py
else
    echo "SKIP: cargo/python3 not available in current environment" >&2
fi

echo "==> [4/4] Android JVM unit tests"
if [ -x ./gradlew ] && [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
    ./gradlew :app:testDebugUnitTest -PskipRustBuild=true
elif command -v gradle >/dev/null 2>&1 && [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
    gradle :app:testDebugUnitTest -PskipRustBuild=true
else
    echo "SKIP: Android SDK or Gradle not available in current environment" >&2
fi
