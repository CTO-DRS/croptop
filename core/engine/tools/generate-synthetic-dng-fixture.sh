#!/usr/bin/env bash
# Recreates the tiny deterministic RAW fixture used by the Android open-path test.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
engine_dir="$(cd -- "$script_dir/.." && pwd)"
destination="${1:-$engine_dir/src/androidTest/assets/raw/synthetic.dng}"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

cat >"$work_dir/generate.cpp" <<'CPP'
#include "native_tests_synthetic_dng.h"

#include <fstream>

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    const auto bytes = croptop::testing::syntheticDng();
    std::ofstream output(argv[1], std::ios::binary);
    output.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
    return output.good() ? 0 : 1;
}
CPP

"${CXX:-c++}" -std=c++20 -I"$engine_dir/src/main/cpp" "$work_dir/generate.cpp" -o "$work_dir/generate"
mkdir -p "$(dirname -- "$destination")"
"$work_dir/generate" "$destination"
echo "generated $destination ($(wc -c <"$destination") bytes)"
