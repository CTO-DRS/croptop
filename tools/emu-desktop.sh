#!/usr/bin/env bash
# Launch the laptop-form-factor test emulator (Android Desktop 37, x86_64, 1920x1080 @160dpi).
#
# Resource-safe defaults — the emulator must not starve the host while Gradle runs:
#   * -gpu host        : render on the host GPU (Intel Arc here). swiftshader_indirect renders on
#                        the CPU and burned ~9 cores + growing RAM, pushing the host into swap.
#   * -memory 3072     : guest RAM cap (Android Desktop idles at ~2 GB).
#   * -cores 4         : leave most cores to Gradle/Kotlin.
#   * quick-boot snapshot kept (fast restarts, bounded disk use).
# Usage: tools/emu-desktop.sh [--window]    (headless by default; screenshots via adb)
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
WINDOW_FLAG="-no-window"
[[ "${1:-}" == "--window" ]] && WINDOW_FLAG=""
exec "$SDK/emulator/emulator" -avd Aether_Desktop_37 $WINDOW_FLAG \
  -gpu host -memory 3072 -cores 4 \
  -no-audio -no-boot-anim \
  -camera-back none -camera-front none
