#!/usr/bin/env bash
# Device-event regression against an in-memory transport, confined to an emulator.
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
serial=${1:-}
if [[ ! "$serial" =~ ^emulator-[0-9]+$ ]]; then
  echo 'Usage: scripts/android-interaction-test.sh emulator-PORT' >&2
  echo 'Use an API 36 emulator with Gboard and the English QWERTY layout.' >&2
  exit 2
fi
sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
adb_bin="$sdk_root/platform-tools/adb"
"$adb_bin" -s "$serial" get-state >/dev/null
ime=$("$adb_bin" -s "$serial" shell settings get secure default_input_method)
if [[ "$ime" != com.google.android.inputmethod.latin/* ]]; then
  echo 'Select Gboard on the emulator before running the Gboard touch regression.' >&2
  exit 2
fi
audio_granted=false
if "$adb_bin" -s "$serial" shell dumpsys package com.lateapex.collie.debug | grep 'android.permission.RECORD_AUDIO: granted=true' >/dev/null; then
  audio_granted=true
fi
previous_keyboard=$("$adb_bin" -s "$serial" shell settings get secure show_ime_with_hard_keyboard)
restore_keyboard() {
  if [[ "$audio_granted" == false ]]; then
    "$adb_bin" -s "$serial" shell pm revoke com.lateapex.collie.debug android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
  fi
  if [[ "$previous_keyboard" == null ]]; then
    "$adb_bin" -s "$serial" shell settings delete secure show_ime_with_hard_keyboard >/dev/null || true
  else
    "$adb_bin" -s "$serial" shell settings put secure show_ime_with_hard_keyboard "$previous_keyboard" >/dev/null || true
  fi
}
trap restore_keyboard EXIT
"$adb_bin" -s "$serial" shell settings put secure show_ime_with_hard_keyboard 1
ANDROID_HOME="$sdk_root" "$repo_root/android/gradlew" -p "$repo_root/android" --no-daemon assembleDebug assembleDebugAndroidTest
"$adb_bin" -s "$serial" install -r "$repo_root/android/app/build/outputs/apk/debug/app-debug.apk"
"$adb_bin" -s "$serial" install -r "$repo_root/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
result=$(mktemp)
# adb may exit zero even when instrumentation reports test failures.
if ! "$adb_bin" -s "$serial" shell am instrument -w -r \
  -e class com.lateapex.collie.ui.NativeInteractionTest \
  com.lateapex.collie.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$result"; then
  rm -f "$result"
  exit 1
fi
if ! grep -Eq '^OK \([0-9]+ tests?\)' "$result"; then
  rm -f "$result"
  exit 1
fi
rm -f "$result"
