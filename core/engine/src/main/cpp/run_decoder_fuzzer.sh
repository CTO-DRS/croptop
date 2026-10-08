#!/usr/bin/env bash
set -euo pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${CROPTOP_FUZZ_BUILD_DIR:-${TMPDIR:-/tmp}/croptop-decoder-fuzz}"
NDK_VERSION="${CROPTOP_FUZZ_NDK_VERSION:-27.1.12297006}"
ANDROID_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
NDK_TOOLCHAIN="${ANDROID_ROOT:+$ANDROID_ROOT/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin}"
FUZZ_CC="${CROPTOP_FUZZ_CC:-${NDK_TOOLCHAIN:+$NDK_TOOLCHAIN/clang}}"
FUZZ_CXX="${CROPTOP_FUZZ_CXX:-${NDK_TOOLCHAIN:+$NDK_TOOLCHAIN/clang++}}"

if [[ -z "$FUZZ_CC" || -z "$FUZZ_CXX" ]]; then
  FUZZ_CC="${CROPTOP_FUZZ_CC:-clang}"
  FUZZ_CXX="${CROPTOP_FUZZ_CXX:-clang++}"
fi

cmake -S "$SOURCE_DIR" -B "$BUILD_DIR" \
  -DCMAKE_BUILD_TYPE=Debug \
  -DCMAKE_C_COMPILER="$FUZZ_CC" \
  -DCMAKE_C_COMPILER_TARGET=x86_64-linux-gnu \
  -DCMAKE_CXX_COMPILER="$FUZZ_CXX" \
  -DCMAKE_CXX_COMPILER_TARGET=x86_64-linux-gnu \
  -DCROPTOP_FETCH_NATIVE_DEPS=ON \
  -DCROPTOP_BUILD_TESTS=OFF \
  -DCROPTOP_BUILD_BENCHMARKS=OFF \
  -DCROPTOP_BUILD_FUZZERS=ON \
  -DCROPTOP_ENABLE_SANITIZERS=OFF
cmake --build "$BUILD_DIR" --target croptop_decoder_fuzzer --parallel

CORPUS_DIR="$BUILD_DIR/fuzz_corpus"
mkdir -p "$CORPUS_DIR"
cp "$SOURCE_DIR"/fuzz_corpus/* "$CORPUS_DIR"/
"$BUILD_DIR/croptop_decoder_fuzzer" \
  -max_len=1048576 \
  -timeout=3 \
  -max_total_time="${CROPTOP_FUZZ_SECONDS:-60}" \
  -artifact_prefix="$BUILD_DIR/" \
  "$CORPUS_DIR"
