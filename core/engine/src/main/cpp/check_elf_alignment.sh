#!/usr/bin/env bash
# Fails unless every PT_LOAD segment of each given ELF shared library is aligned to at least 16 KB (0x4000), which
# Android 15+ devices with 16 KB pages require. Also accepts .apk/.aab/.zip files: their lib/**/*.so are extracted and
# checked (zip alignment of the stored libraries is a separate concern; run zipalign -c -P 16 4 for that).
#   check_elf_alignment.sh libcroptop.so app-release.apk ...
set -euo pipefail

READELF="${READELF:-}"
if [[ -z "$READELF" ]]; then
  for candidate in llvm-readelf readelf; do
    if command -v "$candidate" >/dev/null 2>&1; then READELF="$candidate"; break; fi
  done
fi
if [[ -z "$READELF" && -n "${ANDROID_NDK_HOME:-}" ]]; then
  READELF="$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -name llvm-readelf -type f 2>/dev/null | head -1)"
fi
[[ -n "$READELF" ]] || { echo "check_elf_alignment: no readelf found (set READELF or ANDROID_NDK_HOME)" >&2; exit 2; }
[[ $# -gt 0 ]] || { echo "usage: check_elf_alignment.sh FILE.so|FILE.apk ..." >&2; exit 2; }

failures=0
checked=0
check_one() {
  local file="$1" label="$2" bad=0 seen=0 align
  while read -r align; do
    seen=$((seen + 1))
    if (( align < 16384 )); then bad=1; fi  # readelf prints the alignment as 0x4000; bash arithmetic reads hex
  done < <("$READELF" -lW "$file" | awk '$1 == "LOAD" { print $NF }')
  if (( seen == 0 )); then echo "SKIP  $label (no LOAD segments)"; return; fi
  checked=$((checked + 1))
  if (( bad )); then echo "FAIL  $label: a LOAD segment is aligned below 0x4000"; failures=$((failures + 1)); else echo "OK    $label"; fi
}

for input in "$@"; do
  case "$input" in
    *.apk|*.aab|*.zip)
      work="$(mktemp -d)"
      trap 'rm -rf "$work"' EXIT
      # APKs use lib/<abi>/; Android App Bundles place native libraries in
      # base/lib/<abi>/ (and may have the same layout in feature modules).
      unzip -q -o "$input" 'lib/*' '*/lib/*' -d "$work" 2>/dev/null || true
      while IFS= read -r so; do check_one "$so" "$input:${so#"$work"/}"; done < <(find "$work" -name '*.so' | sort)
      ;;
    *) check_one "$input" "$input" ;;
  esac
done
echo "checked $checked libraries, $failures failing"
(( failures == 0 && checked > 0 ))
