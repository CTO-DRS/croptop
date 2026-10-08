#!/usr/bin/env bash
set -euo pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${CROPTOP_FORMAT_FUZZ_BUILD_DIR:-${TMPDIR:-/tmp}/croptop-format-fuzzer-co3}"
NDK_VERSION="${CROPTOP_FORMAT_FUZZ_NDK_VERSION:-27.1.12297006}"
ANDROID_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
NDK_TOOLCHAIN="${ANDROID_ROOT:+$ANDROID_ROOT/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin}"
FUZZ_CC="${CROPTOP_FORMAT_FUZZ_CC:-${NDK_TOOLCHAIN:+$NDK_TOOLCHAIN/clang}}"
FUZZ_CXX="${CROPTOP_FORMAT_FUZZ_CXX:-${NDK_TOOLCHAIN:+$NDK_TOOLCHAIN/clang++}}"

if [[ -z "$FUZZ_CC" || -z "$FUZZ_CXX" ]]; then
  FUZZ_CC="${CROPTOP_FORMAT_FUZZ_CC:-clang}"
  FUZZ_CXX="${CROPTOP_FORMAT_FUZZ_CXX:-clang++}"
fi

CORPUS_DIR="$BUILD_DIR/format_corpus"
mkdir -p "$CORPUS_DIR" "$BUILD_DIR/artifacts"

cmake -S "$SOURCE_DIR" -B "$BUILD_DIR/build" \
  -DCMAKE_BUILD_TYPE=Debug \
  -DCMAKE_C_COMPILER="$FUZZ_CC" \
  -DCMAKE_C_COMPILER_TARGET=x86_64-linux-gnu \
  -DCMAKE_CXX_COMPILER="$FUZZ_CXX" \
  -DCMAKE_CXX_COMPILER_TARGET=x86_64-linux-gnu \
  -DCROPTOP_FETCH_NATIVE_DEPS=ON \
  -DCROPTOP_BUILD_TESTS=OFF \
  -DCROPTOP_BUILD_BENCHMARKS=OFF \
  -DCROPTOP_BUILD_FUZZERS=ON \
  -DCROPTOP_ENABLE_SANITIZERS=OFF \
  -DCROPTOP_ENABLE_LTO=OFF

cmake --build "$BUILD_DIR/build" --target croptop_format_fuzzer --parallel

python3 - "$SOURCE_DIR/croptop_v1_fixture.inc" "$CORPUS_DIR/croptop-v1.zip" <<'PYTHON_EOF'
import pathlib
import re
import sys

source = pathlib.Path(sys.argv[1]).read_text()
data = bytes(int(value, 16) for value in re.findall(r"0x([0-9a-fA-F]{2})", source))
if not data.startswith(b"PK\x03\x04"):
    raise SystemExit("frozen v1 fixture did not decode to a ZIP local header")
pathlib.Path(sys.argv[2]).write_bytes(data)
print(f"seed: {sys.argv[2]} ({len(data)} bytes)")
PYTHON_EOF

"$BUILD_DIR/build/croptop_format_fuzzer" \
  -max_len=1048576 \
  -timeout=3 \
  -max_total_time="${CROPTOP_FORMAT_FUZZ_SECONDS:-60}" \
  -artifact_prefix="$BUILD_DIR/artifacts/" \
  "$CORPUS_DIR"
