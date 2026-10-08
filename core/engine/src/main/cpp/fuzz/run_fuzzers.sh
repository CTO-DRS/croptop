#!/usr/bin/env bash
#   core/engine/src/main/cpp/fuzz/run_fuzzers.sh [name ...]      # default: every target
# Env: CROPTOP_FUZZ_SECONDS (per target, default 60), CROPTOP_FUZZ_JOBS (parallel targets, default 1),
#      CROPTOP_FUZZ_BUILD_DIR (default $TMPDIR/croptop-format-fuzzers), CROPTOP_FUZZ_CC/CXX or CC/CXX.
# Host-native clang/clang++ are the default. Android NDK cross-compilation is opt-in by explicitly
# setting CROPTOP_FUZZ_CC/CXX (and, if needed, CROPTOP_FUZZ_COMPILER_TARGET); cross-built binaries
# need a matching runner and cannot be executed directly by this host campaign script.
# Each target keeps its growing corpus in $BUILD_DIR/corpus/<name> (seeded from fuzz/corpus/<name>) and
# writes crashes as $BUILD_DIR/artifacts/<name>/crash-*. The exit status is non-zero if any target crashed.
set -euo pipefail

FUZZ_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="$(dirname "$FUZZ_DIR")"
BUILD_DIR="${CROPTOP_FUZZ_BUILD_DIR:-${TMPDIR:-/tmp}/croptop-format-fuzzers}"
FUZZ_CC="${CROPTOP_FUZZ_CC:-${CC:-clang}}"
FUZZ_CXX="${CROPTOP_FUZZ_CXX:-${CXX:-clang++}}"

TARGETS=("$@")
if [[ ${#TARGETS[@]} -eq 0 ]]; then
  TARGETS=(tiff psd codec_extra abr lut xmp icc font metadata shape_record avif ultrahdr)
fi

CONFIGURE_ARGS=(
  -S "$SOURCE_DIR" -B "$BUILD_DIR"
  -DCMAKE_BUILD_TYPE=Debug
  -DCMAKE_C_COMPILER="$FUZZ_CC"
  -DCMAKE_CXX_COMPILER="$FUZZ_CXX"
  -DCROPTOP_FETCH_NATIVE_DEPS=ON
  -DCROPTOP_BUILD_TESTS=OFF
  -DCROPTOP_BUILD_BENCHMARKS=OFF
  -DCROPTOP_BUILD_FUZZERS=ON
  -DCROPTOP_ENABLE_SANITIZERS=OFF
  -DCROPTOP_ENABLE_LTO=OFF
)
if [[ -n "${CROPTOP_FUZZ_COMPILER_TARGET:-}" ]]; then
  CONFIGURE_ARGS+=(
    -DCMAKE_C_COMPILER_TARGET="$CROPTOP_FUZZ_COMPILER_TARGET"
    -DCMAKE_CXX_COMPILER_TARGET="$CROPTOP_FUZZ_COMPILER_TARGET"
  )
fi
cmake "${CONFIGURE_ARGS[@]}"
cmake --build "$BUILD_DIR" --parallel "${CROPTOP_FUZZ_BUILD_JOBS:-4}" \
  --target "${TARGETS[@]/#/croptop_fuzz_}"

run_one() {
  local name="$1" corpus="$BUILD_DIR/corpus/$1" artifacts="$BUILD_DIR/artifacts/$1"
  local binary="$BUILD_DIR/fuzz/croptop_fuzz_$1"
  mkdir -p "$corpus" "$artifacts"
  if [[ -d "$FUZZ_DIR/corpus/$name" ]]; then cp -n "$FUZZ_DIR/corpus/$name"/* "$corpus"/ 2>/dev/null || true; fi
  if [[ ! -x "$binary" ]]; then
    printf 'fuzzer executable is missing: %s\n' "$binary" >&2
    return 1
  fi
  "$binary" \
    -max_len="${CROPTOP_FUZZ_MAX_LEN:-1048576}" \
    -rss_limit_mb=2048 \
    -timeout=10 \
    -max_total_time="${CROPTOP_FUZZ_SECONDS:-60}" \
    -print_final_stats=1 \
    -artifact_prefix="$artifacts/" \
    "$corpus" > "$BUILD_DIR/$name.log" 2>&1
}

status=0
running=0
for name in "${TARGETS[@]}"; do
  run_one "$name" &
  running=$((running + 1))
  if (( running >= ${CROPTOP_FUZZ_JOBS:-1} )); then
    if ! wait -n; then status=1; fi
    running=$((running - 1))
  fi
done
while (( running > 0 )); do
  if ! wait -n; then status=1; fi
  running=$((running - 1))
done

for name in "${TARGETS[@]}"; do
  summary="$(grep -E '^Done [0-9]+ runs|^==[0-9]+==ERROR|^ERROR: (AddressSanitizer|libFuzzer)|^SUMMARY: AddressSanitizer|runtime error:|deadly signal' "$BUILD_DIR/$name.log" | tail -1 || true)"
  printf '%-13s %s\n' "$name" "${summary:-no final stats; inspect $BUILD_DIR/$name.log}"
done
exit "$status"
