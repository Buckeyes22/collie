#!/usr/bin/env bash
# Install and exercise a reviewed APK pair on one disposable, isolated lab AVD.
set -euo pipefail

die() { echo "$*" >&2; exit 1; }
usage() {
  echo 'Usage: android-device-hardening.sh LAB_ROOT APK_DIR PROFILE' >&2
  echo 'Profiles: 26-phone, 36-phone, 36-foldable' >&2
  exit 2
}
[[ $# == 3 ]] || usage
lab=$1
apk_dir=$2
profile=$3
[[ "$lab" == /* && "$lab" != / && -d "$lab" && ! -L "$lab" ]] || die 'LAB_ROOT must be an existing absolute non-symlink directory.'
[[ -f "$lab/.collie-emulator-lab" && ! -L "$lab/.collie-emulator-lab" ]] || die 'LAB_ROOT is not marked as a Collie emulator lab.'
[[ $(stat -c %u "$lab") == "$UID" ]] || die 'LAB_ROOT must be owned by the current account.'
[[ "$apk_dir" == /* && "$apk_dir" != / && -d "$apk_dir" && ! -L "$apk_dir" ]] || die 'APK_DIR must be an existing absolute non-symlink directory.'
[[ $(stat -c %u "$apk_dir") == "$UID" ]] || die 'APK_DIR must be owned by the current account.'
[[ $(hostname -s) == z2 ]] || die 'This hardening script is restricted to the dedicated z2 host.'
[[ $(uname -sm) == 'Linux x86_64' ]] || die 'Linux x86_64 is required.'

case "$profile" in
  26-phone) port=5660; expected_api=26 ;;
  36-phone) port=5662; expected_api=36 ;;
  36-foldable) port=5664; expected_api=36 ;;
  *) usage ;;
esac
lab=$(realpath -e -- "$lab")
apk_dir=$(realpath -e -- "$apk_dir")
export ANDROID_HOME="$lab/sdk" ANDROID_SDK_ROOT="$lab/sdk"
export ANDROID_AVD_HOME="$lab/avd" ANDROID_USER_HOME="$lab/android-user"
export ANDROID_ADB_SERVER_PORT=5038 ADB_SERVER_SOCKET=tcp:localhost:5038
adb="$ANDROID_HOME/platform-tools/adb"
emulator="$ANDROID_HOME/emulator/emulator"
aapt="$ANDROID_HOME/build-tools/36.0.0/aapt"
for file in "$adb" "$emulator" "$aapt"; do [[ -x "$file" ]] || die "Missing executable: $file"; done
[[ -d "$ANDROID_AVD_HOME/collie-$profile.avd" ]] || die 'Bootstrap this isolated AVD profile first.'
for tool in flock sha256sum timeout setsid realpath stat awk sed grep tr date mktemp od; do
  command -v "$tool" >/dev/null || die "Missing required tool: $tool"
done

# Serialize lab access using the same lock as android-emulator-lab.sh.
exec 9> "/tmp/collie-android-lab-$UID.lock"
flock -n 9 || die 'Another Collie Android lab operation is already running.'

# The manifest is intentionally limited to two basenames; no paths or extra APKs are accepted.
expected_debug=app-debug.apk
expected_test=app-debug-androidTest.apk
[[ -f "$apk_dir/apks.sha256" && ! -L "$apk_dir/apks.sha256" ]] || die 'APK_DIR must contain apks.sha256.'
[[ -f "$apk_dir/$expected_debug" && ! -L "$apk_dir/$expected_debug" ]] || die "Missing regular $expected_debug."
[[ -f "$apk_dir/$expected_test" && ! -L "$apk_dir/$expected_test" ]] || die "Missing regular $expected_test."
if [[ "$profile" == 36-phone ]]; then
  [[ -f "$apk_dir/interaction-methods.txt" && ! -L "$apk_dir/interaction-methods.txt" ]] || die '36-phone requires interaction-methods.txt.'
fi
mapfile -t manifest_lines < "$apk_dir/apks.sha256"
[[ ${#manifest_lines[@]} == 2 ]] || die 'apks.sha256 must contain exactly two lines.'
seen_debug=false
seen_test=false
for line in "${manifest_lines[@]}"; do
  [[ "$line" =~ ^([0-9a-f]{64})[[:space:]][[:space:]](app-debug\.apk|app-debug-androidTest\.apk)$ ]] ||
    die 'Manifest lines must be lowercase SHA-256 plus an exact APK basename.'
  case "${BASH_REMATCH[2]}" in
    app-debug.apk) [[ "$seen_debug" == false ]] || die 'Duplicate app-debug.apk manifest entry.'; seen_debug=true ;;
    app-debug-androidTest.apk) [[ "$seen_test" == false ]] || die 'Duplicate androidTest manifest entry.'; seen_test=true ;;
  esac
done
[[ "$seen_debug" == true && "$seen_test" == true ]] || die 'Manifest must cover both APKs exactly once.'
while IFS= read -r entry; do
  case "$entry" in
    app-debug.apk|app-debug-androidTest.apk|apks.sha256) ;;
    interaction-methods.txt) ;;
    *) die "Unexpected APK_DIR entry: $entry" ;;
  esac
done < <(find "$apk_dir" -mindepth 1 -maxdepth 1 -printf '%f\n')
(
  cd "$apk_dir"
  sha256sum --check --strict apks.sha256
)

badging=$("$aapt" dump badging "$apk_dir/$expected_debug")
version=$(sed -n "s/^package:.*versionName='\\([^']*\\)'.*/\\1/p" <<< "$badging" | head -n 1)
[[ -n "$version" && "$version" =~ ^[A-Za-z0-9._+-]+$ ]] || die 'Could not read a safe Android versionName from the debug APK.'
run=$(mktemp -d "$lab/hardening-$version-$profile-XXXXXX")
mkdir -p "$run/apks"
cp -- "$apk_dir/$expected_debug" "$apk_dir/$expected_test" "$apk_dir/apks.sha256" "$run/apks/"
if [[ "$profile" == 36-phone ]]; then cp -- "$apk_dir/interaction-methods.txt" "$run/apks/"; fi
(
  cd "$run/apks"
  sha256sum --check --strict apks.sha256
  sha256sum "$expected_debug" "$expected_test" > copied-apks.sha256
)
copied_badging=$("$aapt" dump badging "$run/apks/$expected_debug")
[[ "$copied_badging" == "$badging" ]] || die 'APK metadata changed during copying.'
grep -q "^package: name='com.lateapex.collie.debug' " <<< "$copied_badging" || die 'Unexpected debug application ID.'
test_badging=$("$aapt" dump badging "$run/apks/$expected_test")
grep -q "^package: name='com.lateapex.collie.debug.test' " <<< "$test_badging" || die 'Unexpected instrumentation application ID.'
sha256sum "$run/apks/apks.sha256" > "$run/manifest.sha256"
printf '%s\n' "$version" > "$run/app-version.txt"
printf '%s\n' "$profile" > "$run/profile.txt"
printf '%s\n' "$expected_api" > "$run/expected-api.txt"
date -u +%FT%TZ > "$run/started-utc.txt"
"$emulator" -version > "$run/emulator-version.txt" 2>&1
"$ANDROID_HOME/cmdline-tools/pinned/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --list_installed > "$run/sdk-packages.txt"
"$adb" -P 5038 start-server 9>&-
serial="emulator-$port"
if "$adb" -P 5038 devices | awk '{print $1}' | grep -qx "$serial"; then
  die "Emulator port $port is already occupied; refusing to control that device."
fi

emulator_pid=''
cleanup() {
  [[ -n "$emulator_pid" ]] || return 0
  if kill -0 "$emulator_pid" 2>/dev/null; then
    timeout 5 "$adb" -P 5038 -s "$serial" emu kill > "$run/emulator-shutdown.txt" 2>&1 || true
    for _ in {1..20}; do
      kill -0 -- "-$emulator_pid" 2>/dev/null || break
      sleep 0.25
    done
  fi
  # setsid owns this process group. TERM it, wait briefly, then KILL only that group.
  kill -TERM -- "-$emulator_pid" 2>/dev/null || true
  for _ in {1..20}; do
    kill -0 -- "-$emulator_pid" 2>/dev/null || break
    sleep 0.25
  done
  if kill -0 -- "-$emulator_pid" 2>/dev/null; then
    kill -KILL -- "-$emulator_pid" 2>/dev/null || true
  fi
  wait "$emulator_pid" 2>/dev/null || true
  emulator_pid=''
}
trap cleanup EXIT
setsid "$emulator" -avd "collie-$profile" -port "$port" -no-window -no-audio \
  -no-snapshot -wipe-data -accel on -gpu swiftshader > "$run/emulator.log" 2>&1 9>&- &
emulator_pid=$!
timeout 180 "$adb" -P 5038 -s "$serial" wait-for-device
booted=false
for _ in {1..120}; do
  if [[ $("$adb" -P 5038 -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then
    booted=true
    break
  fi
  kill -0 "$emulator_pid" 2>/dev/null || die 'Owned emulator exited; inspect emulator.log.'
  sleep 2
done
[[ "$booted" == true ]] || die 'Emulator did not finish booting.'

adb_cmd() { "$adb" -P 5038 -s "$serial" "$@"; }
capture_png() {
  local label=$1 signature png_width png_height expected_size
  # Multi-display screencap may put a warning on stdout before its binary PNG stream.
  # Write the image on-device, then pull it so diagnostics never corrupt the image bytes.
  adb_cmd shell screencap -p "/sdcard/collie-$label.png" > "$run/$label-capture.log" 2>&1
  adb_cmd pull "/sdcard/collie-$label.png" "$run/$label.png"
  signature=$(od -An -tx1 -N8 "$run/$label.png" | tr -d '[:space:]')
  [[ "$signature" == 89504e470d0a1a0a ]] || die "$label is not a valid PNG capture."
  read -r png_width png_height <<< "$(od -An -tu4 --endian=big -j16 -N8 "$run/$label.png")"
  adb_cmd shell wm size > "$run/$label-display-size.txt"
  expected_size=$(sed -n 's/^Physical size: //p' "$run/$label-display-size.txt" | tr -d '\r')
  [[ "${png_width}x${png_height}" == "$expected_size" ]] || die "$label dimensions do not match the active display."
}
adb_cmd shell getprop > "$run/device-properties.txt"
actual_api=$(adb_cmd shell getprop ro.build.version.sdk | tr -d '\r')
printf '%s\n' "$actual_api" > "$run/actual-api.txt"
[[ "$actual_api" == "$expected_api" ]] || die "Expected API $expected_api, got $actual_api."
adb_cmd shell pm list packages > "$run/packages.txt"
adb_cmd shell ime list -s > "$run/input-methods.txt"
adb_cmd shell wm size > "$run/default-display-size.txt"
adb_cmd shell wm density > "$run/default-display-density.txt"

# Install only the copied pair whose manifest was checked before and after copying.
(
  cd "$run/apks"
  sha256sum --check --strict apks.sha256
)
adb_cmd install -r "$run/apks/$expected_debug"
adb_cmd install -r "$run/apks/$expected_test"
for animation in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb_cmd shell settings put global "$animation" 0
  [[ $(adb_cmd shell settings get global "$animation" | tr -d '\r') == 0 ]] || die "Failed setting $animation=0."
done
adb_cmd shell settings put secure show_ime_with_hard_keyboard 1
adb_cmd shell settings get global window_animation_scale > "$run/window-animation-scale.txt"
adb_cmd shell settings get global transition_animation_scale > "$run/transition-animation-scale.txt"
adb_cmd shell settings get global animator_duration_scale > "$run/animator-duration-scale.txt"
adb_cmd shell settings get secure show_ime_with_hard_keyboard > "$run/show-ime-with-hard-keyboard.txt"

runner=com.lateapex.collie.debug.test/androidx.test.runner.AndroidJUnitRunner
run_tests() {
  local label=$1 selector=$2 logfile="$run/$1.log"
  echo "Running $label: $selector" | tee "$run/$label-scope.txt"
  timeout 900 "$adb" -P 5038 -s "$serial" shell am instrument -w -r -e class "$selector" "$runner" \
    2>&1 | tee "$logfile"
  tr -d '\r' < "$logfile" | grep -Eq '^OK \([1-9][0-9]* tests?\)$' || die "$label did not report a passing test run."
}
baseline=com.lateapex.collie.ui.NativeLaunchSmokeTest,com.lateapex.collie.ui.SystemBarInsetsDeviceTest,com.lateapex.collie.ui.AgentIconsDeviceTest,com.lateapex.collie.data.AndroidKeystoreConnectionStoreTest
run_tests baseline "$baseline"

if [[ "$profile" == 36-phone ]]; then
  # The full selector is reviewed alongside the exact APK pair and is restricted to this suite.
  interaction_selector=$(tr -d '[:space:]' < "$run/apks/interaction-methods.txt")
  selector_pattern='^com\.lateapex\.collie\.ui\.NativeInteractionTest(#([A-Za-z0-9_]+))?(,com\.lateapex\.collie\.ui\.NativeInteractionTest(#([A-Za-z0-9_]+))?)*$'
  [[ "$interaction_selector" =~ $selector_pattern ]] || die 'interaction-methods.txt contains an unsupported test selector.'
  if grep -q 'gboardKeysReachDirectTyping' <<< "$interaction_selector"; then
    grep -q 'package:com.google.android.inputmethod.latin' "$run/packages.txt" || die 'The full phone interaction selector requires installed Gboard.'
    grep -Fq 'com.google.android.inputmethod.latin' "$run/input-methods.txt" || die 'Gboard is not available as an input method.'
  fi
  run_tests interaction-default "$interaction_selector"
fi

# 200% text at 540 dpi yields about 320 dp on the 1080 px API 36 phone AVD.
adb_cmd shell settings put system font_scale 2.0
[[ $(adb_cmd shell settings get system font_scale | tr -d '\r') == 2.0 ]] || die 'System font scale did not reach 2.0.'
if [[ "$profile" == 36-phone ]]; then
  adb_cmd shell wm density 540
  adb_cmd shell wm density > "$run/narrow-large-text-display-density.txt"
  adb_cmd shell wm size > "$run/narrow-large-text-display-size.txt"
  physical_width=$(sed -n 's/^Physical size: \([0-9][0-9]*\)x.*/\1/p' "$run/narrow-large-text-display-size.txt" | head -n 1)
  [[ "$physical_width" =~ ^[0-9]+$ ]] || die 'Could not determine API 36 phone display width.'
  dp_width=$((physical_width * 160 / 540))
  printf '%s px at 540 dpi = %s dp\n' "$physical_width" "$dp_width" > "$run/narrow-large-text-width.txt"
  (( dp_width <= 360 && dp_width >= 280 )) || die "540 dpi did not create the intended narrow width: ${dp_width}dp."
  adb_cmd shell wm density > "$run/narrow-large-text-display-density.txt"
  grep -q 'Override density: 540' "$run/narrow-large-text-display-density.txt" || die 'The phone density override was not applied.'
  adb_cmd shell am force-stop com.lateapex.collie.debug
  run_tests hardening-ui-large-text com.lateapex.collie.ui.HardeningUiDeviceTest
  narrow_selector='com.lateapex.collie.ui.NativeInteractionTest#typedReplyAndSendReachTransportExactlyOnce,com.lateapex.collie.ui.NativeInteractionTest#tapOpensSwitcherAndBackDismisses,com.lateapex.collie.ui.NativeInteractionTest#dashboardSettingsAndBackNavigateThroughVisibleButtons,com.lateapex.collie.ui.NativeInteractionTest#dashboardSpacePaneAndBackButtonsFormAWorkingRoute,com.lateapex.collie.ui.NativeInteractionTest#settingsBehaviorAndDraftControlsPersistTheirChoices,com.lateapex.collie.ui.NativeInteractionTest#readOnlyPaneKeepsNavigationButDisablesTypingAndKeys,com.lateapex.collie.ui.NativeInteractionTest#networkFailureKeepsTheMirrorAndRecoversWithoutWrites,com.lateapex.collie.ui.NativeInteractionTest#paneFindSearchAndBackRestoreComposer'
  run_tests narrow-large-text "$narrow_selector"
elif [[ "$profile" == 36-foldable ]]; then
  fold_ui_selector='com.lateapex.collie.ui.HardeningUiDeviceTest#settingsDraftControlsRemainTouchReachableAtTwoHundredPercentSystemFont,com.lateapex.collie.ui.HardeningUiDeviceTest#scaledPackFormationDrawsAndUsesShiftedTouchAndAccessibilityBounds,com.lateapex.collie.ui.HardeningUiDeviceTest#setupActionsKeepFullLabelsVisibleAtTwoHundredPercentSystemFont'
  adb_cmd shell am force-stop com.lateapex.collie.debug
  run_tests hardening-ui-large-text "$fold_ui_selector"
else
  adb_cmd shell am force-stop com.lateapex.collie.debug
  run_tests hardening-ui-large-text com.lateapex.collie.ui.HardeningUiDeviceTest
fi

if [[ "$profile" == 36-foldable ]]; then
  # Keep posture evidence and require display geometry to prove each transition occurred.
  adb_cmd shell cmd device_state print-states > "$run/device-states-supported.txt"
  adb_cmd shell cmd device_state print-state > "$run/active-device-state-before.txt"
  state_before=$(tr -d '[:space:]' < "$run/active-device-state-before.txt")
  [[ "$state_before" =~ ^[0-9]+$ ]] || die 'Could not read the active device-state ID before folding.'
  adb_cmd shell dumpsys device_state > "$run/device-state-before.txt"
  adb_cmd shell wm size > "$run/fold-display-before.txt"
  adb_cmd emu fold > "$run/fold-command.txt" 2>&1
  sleep 3
  adb_cmd shell cmd device_state print-state > "$run/active-device-state-folded.txt"
  state_folded=$(tr -d '[:space:]' < "$run/active-device-state-folded.txt")
  [[ "$state_folded" =~ ^[0-9]+$ ]] || die 'Could not read the active device-state ID after folding.'
  adb_cmd shell dumpsys device_state > "$run/device-state-folded.txt"
  adb_cmd shell wm size > "$run/fold-display-after.txt"
  [[ "$state_before" != "$state_folded" ]] || die 'Fold did not change the active device-state ID.'
  before_size=$(sed -n 's/^Physical size: //p' "$run/fold-display-before.txt" | head -n 1)
  folded_size=$(sed -n 's/^Physical size: //p' "$run/fold-display-after.txt" | head -n 1)
  [[ -n "$before_size" && -n "$folded_size" && "$before_size" != "$folded_size" ]] || die 'Fold command did not produce verifiably different physical display geometry.'
  adb_cmd shell input keyevent KEYCODE_WAKEUP
  adb_cmd shell wm dismiss-keyguard
  adb_cmd shell am start -n com.lateapex.collie.debug/com.lateapex.collie.ui.MainActivity
  sleep 2
  capture_png connection-folded
  adb_cmd shell uiautomator dump /sdcard/collie-hardening-folded.xml > "$run/folded-uiautomator-dump.txt"
  adb_cmd pull /sdcard/collie-hardening-folded.xml "$run/connection-folded.xml"
  grep -Fq ':id/setup_settings_button"' "$run/connection-folded.xml" || die 'Folded capture does not show the native setup surface.'
  adb_cmd emu unfold > "$run/unfold-command.txt" 2>&1
  sleep 3
  adb_cmd shell cmd device_state print-state > "$run/active-device-state-unfolded.txt"
  state_unfolded=$(tr -d '[:space:]' < "$run/active-device-state-unfolded.txt")
  [[ "$state_unfolded" =~ ^[0-9]+$ ]] || die 'Could not read the active device-state ID after unfolding.'
  adb_cmd shell dumpsys device_state > "$run/device-state-unfolded.txt"
  adb_cmd shell wm size > "$run/unfold-display-after.txt"
  [[ "$state_unfolded" == "$state_before" ]] || die 'Unfold did not restore the original active device-state ID.'
  unfolded_size=$(sed -n 's/^Physical size: //p' "$run/unfold-display-after.txt" | head -n 1)
  [[ "$unfolded_size" == "$before_size" ]] || die 'Unfold command did not restore the original physical display geometry.'
  adb_cmd shell input keyevent KEYCODE_WAKEUP
  adb_cmd shell wm dismiss-keyguard
  adb_cmd shell am start -n com.lateapex.collie.debug/com.lateapex.collie.ui.MainActivity
  sleep 2
  capture_png connection-unfolded
  adb_cmd shell uiautomator dump /sdcard/collie-hardening-unfolded.xml > "$run/unfolded-uiautomator-dump.txt"
  adb_cmd pull /sdcard/collie-hardening-unfolded.xml "$run/connection-unfolded.xml"
  grep -Fq ':id/setup_settings_button"' "$run/connection-unfolded.xml" || die 'Unfolded capture does not show the native setup surface.'
fi

# Capture a large-text screen before restoring per-run settings; -wipe-data isolates the next run.
if [[ "$profile" != 36-foldable ]]; then
  adb_cmd shell am start -n com.lateapex.collie.debug/com.lateapex.collie.ui.MainActivity
  sleep 2
  capture_png connection-large-text
  adb_cmd shell uiautomator dump /sdcard/collie-hardening.xml > "$run/large-text-uiautomator-dump.txt"
  adb_cmd pull /sdcard/collie-hardening.xml "$run/connection-large-text.xml"
fi
adb_cmd shell settings put system font_scale 1.0
if [[ "$profile" == 36-phone ]]; then adb_cmd shell wm density reset; fi
adb_cmd shell settings get system font_scale > "$run/restored-font-scale.txt"
[[ $(tr -d '\r' < "$run/restored-font-scale.txt") == 1.0 ]] || die 'System font scale was not restored to 1.0.'
if [[ "$profile" == 36-phone ]]; then adb_cmd shell wm density > "$run/restored-display-density.txt"; fi
if [[ "$profile" == 36-phone && ${COLLIE_LAB_TALKBACK:-0} == 1 ]]; then
  grep -qx 'package:com.google.android.marvin.talkback' "$run/packages.txt" || die 'TalkBack is not installed on this lab image.'
  adb_cmd shell settings get secure enabled_accessibility_services > "$run/accessibility-services-before.txt"
  adb_cmd shell settings get secure accessibility_enabled > "$run/accessibility-enabled-before.txt"
  adb_cmd shell settings put secure enabled_accessibility_services \
    com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService
  adb_cmd shell settings put secure accessibility_enabled 1
  sleep 5
  adb_cmd shell dumpsys accessibility > "$run/accessibility-connected.txt"
  run_tests talkback-platform com.lateapex.collie.ui.TalkBackDeviceTest
  adb_cmd shell dumpsys accessibility > "$run/accessibility-after-test.txt"
  # This is a fresh disposable image. Restore its original service settings after the check.
  for setting in enabled_accessibility_services accessibility_enabled; do
    case "$setting" in
      enabled_accessibility_services) previous=$(tr -d '\r' < "$run/accessibility-services-before.txt") ;;
      accessibility_enabled) previous=$(tr -d '\r' < "$run/accessibility-enabled-before.txt") ;;
    esac
    if [[ "$previous" == null ]]; then
      adb_cmd shell settings delete secure "$setting"
    else
      adb_cmd shell settings put secure "$setting" "$previous"
    fi
  done
fi
(
  cd "$run/apks"
  sha256sum --check --strict apks.sha256
  sha256sum "$expected_debug" "$expected_test" > "$run/installed-apks-final.sha256"
)
date -u +%FT%TZ > "$run/finished-utc.txt"
echo "Hardening run passed; evidence at $run."
