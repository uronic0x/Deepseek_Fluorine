#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
cargo fmt --check
cargo test
cargo build
cargo clippy -- -D warnings
node --test tests/core.test.mjs
python3 tests/integration.py
