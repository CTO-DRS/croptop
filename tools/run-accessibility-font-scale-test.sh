#!/usr/bin/env bash
set -euo pipefail

device_serial="${ANDROID_SERIAL:-emulator-5554}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

original_font_scale="$(adb -s "$device_serial" shell settings get system font_scale | tr -d '\r')"
if [[ ! "$original_font_scale" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  printf 'Could not read a valid font_scale from %s: %s\n' "$device_serial" "$original_font_scale" >&2
  exit 1
fi

restore_font_scale() {
  adb -s "$device_serial" shell settings put system font_scale "$original_font_scale" >/dev/null
}
trap restore_font_scale EXIT

adb -s "$device_serial" shell settings put system font_scale 2.0
./gradlew \
  --no-daemon \
  --max-workers=1 \
  --project-cache-dir /tmp/croptop-cl-m8-accessibility-traversal-cache \
  -Pcroptop.buildDir=build-cl-m8-accessibility-traversal \
  :core:designsystem:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.croptop.core.designsystem.FontScaleAccessibilityDeviceTest
