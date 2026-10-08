#!/usr/bin/env bash
set -euo pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${CROPTOP_NATIVE_BUILD_DIR:-${TMPDIR:-/tmp}/croptop-native-host}"

cmake -S "$SOURCE_DIR" -B "$BUILD_DIR" \
  -DCMAKE_BUILD_TYPE=Debug \
  -DCROPTOP_FETCH_NATIVE_DEPS=ON \
  -DCROPTOP_BUILD_TESTS=ON \
  -DCROPTOP_BUILD_BENCHMARKS=ON \
  -DCROPTOP_ENABLE_SANITIZERS=ON
cmake --build "$BUILD_DIR" --parallel "${CMAKE_BUILD_PARALLEL_LEVEL:-2}"
ctest --test-dir "$BUILD_DIR" --output-on-failure
"$BUILD_DIR/croptop_native_benchmarks" --benchmark_min_time=0.01s
