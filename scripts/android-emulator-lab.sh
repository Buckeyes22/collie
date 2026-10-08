#!/usr/bin/env bash
# Run only on the dedicated lab host. All Android state stays below the supplied lab root.
set -euo pipefail

die() { echo "$*" >&2; exit 1; }
usage() {
  echo 'Usage: android-emulator-lab.sh probe | bootstrap LAB_ROOT | validate LAB_ROOT CHECKOUT PROFILE'
  echo 'Profiles: 26-phone, 36-phone, 36-foldable. LAB_ROOT and CHECKOUT must be absolute paths.'
}
action=${1:-}
if [[ "$action" == probe ]]; then
  hostname
  uname -sr
  getconf _NPROCESSORS_ONLN
  free -h
  df -h /
  id
  if [[ -r /dev/kvm && -w /dev/kvm ]]; then echo 'KVM accessible'; else echo 'KVM inaccessible'; fi
  for tool in java adb emulator sdkmanager bun; do command -v "$tool" || true; done
  exit 0
fi
[[ "$action" == bootstrap || "$action" == validate ]] || { usage; exit 2; }
[[ $(hostname -s) == z2 ]] || die 'This lab script is restricted to the dedicated z2 host.'
[[ $(uname -sm) == 'Linux x86_64' ]] || die 'Linux x86_64 is required.'
lab=${2:-}
[[ "$lab" == /* && "$lab" != / && ! -L "$lab" ]] || die 'Supply an absolute, non-symlink lab directory.'
[[ ! -e "$lab" || -f "$lab/.collie-emulator-lab" ]] || die 'Refusing an existing, unmarked directory.'
[[ -n ${JAVA_HOME:-} && -x "$JAVA_HOME/bin/java" ]] || die 'Set JAVA_HOME to an existing JDK 21.'
"$JAVA_HOME/bin/java" -version 2>&1 | head -n 1 | grep -Eq 'version "21[.\"]' || die 'JDK 21 is required.'
[[ -r /dev/kvm && -w /dev/kvm ]] || die 'The lab account needs existing read/write KVM access.'
for tool in curl unzip sha256sum timeout flock tar setsid; do command -v "$tool" >/dev/null || die "Missing $tool"; done
mkdir -p "$lab"
touch "$lab/.collie-emulator-lab"
# ADB's dedicated port is shared across lab roots; serialize all runs for this account.
exec 9> "/tmp/collie-android-lab-$UID.lock"
flock -n 9 || die 'Another Collie lab operation is already running.'
export ANDROID_HOME="$lab/sdk" ANDROID_SDK_ROOT="$lab/sdk"
export ANDROID_USER_HOME="$lab/android-user" ANDROID_AVD_HOME="$lab/avd"
export GRADLE_USER_HOME="$lab/gradle"
export ANDROID_ADB_SERVER_PORT=5038 ADB_SERVER_SOCKET=tcp:localhost:5038
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME" "$GRADLE_USER_HOME"
sdkmanager="$ANDROID_HOME/cmdline-tools/pinned/bin/sdkmanager"
avdmanager="$ANDROID_HOME/cmdline-tools/pinned/bin/avdmanager"
adb="$ANDROID_HOME/platform-tools/adb"

if [[ "$action" == bootstrap ]]; then
  if [[ ! -x "$sdkmanager" ]]; then
    archive="$lab/commandlinetools-linux-15859902_latest.zip"
    curl --fail --location --retry 3 --output "$archive" \
      https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
    echo "4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  $archive" | sha256sum --check
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    staging=$(mktemp -d "$lab/tools.XXXXXX")
    unzip -q "$archive" -d "$staging"
    mv "$staging/cmdline-tools" "$ANDROID_HOME/cmdline-tools/pinned"
    rmdir "$staging"
  fi
  # Feed finite input so pipefail does not interpret yes's SIGPIPE as a license failure.
  "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses < <(printf 'y\n%.0s' {1..100})
  "$sdkmanager" --sdk_root="$ANDROID_HOME" \
    platform-tools emulator 'platforms;android-36' 'build-tools;36.0.0' \
    'system-images;android-26;google_apis;x86_64' 'system-images;android-36;google_apis;x86_64'
  "$ANDROID_HOME/emulator/emulator" -accel-check
  "$sdkmanager" --sdk_root="$ANDROID_HOME" --list_installed > "$lab/sdk-packages.txt"
  "$avdmanager" list device > "$lab/device-profiles.txt"
  for spec in '26-phone:26:pixel_2' '36-phone:36:pixel_7' '36-foldable:36:pixel_fold'; do
    IFS=: read -r profile api device <<< "$spec"
    name="collie-$profile"
    if [[ ! -d "$ANDROID_AVD_HOME/$name.avd" ]]; then
      printf 'no\n' | "$avdmanager" create avd --name "$name" --device "$device" \
        --package "system-images;android-$api;google_apis;x86_64"
    fi
  done
  echo "Lab prepared at $lab; emulator acceptance has not run."
  exit 0
fi

checkout=${3:-}
profile=${4:-}
[[ "$checkout" == /* && -f "$checkout/android/gradlew" ]] || die 'Supply an absolute checkout containing Android sources.'
case "$profile" in
  26-phone) port=5660; expected_api=26 ;;
  36-phone) port=5662; expected_api=36 ;;
  36-foldable) port=5664; expected_api=36 ;;
  *) usage; exit 2 ;;
esac
[[ -x "$adb" && -d "$ANDROID_AVD_HOME/collie-$profile.avd" ]] || die 'Bootstrap the isolated lab first.'
run=$(mktemp -d "$lab/run-$profile-XXXXXX")
mkdir -p "$run/source/android/app"
# Copy only build inputs; never reuse the source checkout's SDK setting or build caches.
tar -C "$checkout/android" -cf - app/src app/build.gradle.kts app/proguard-rules.pro \
  app/gradle.lockfile gradle gradlew gradlew.bat build.gradle.kts settings.gradle.kts \
  gradle.properties | tar -C "$run/source/android" -xf -
tar -C "$checkout" -cf - web/src/lib/agent-commands.ts web/src/fixtures/panes |
  tar -C "$run/source" -xf -
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$run/source/android/local.properties"
build_root="$run/source/android"
serial="emulator-$port"
export ANDROID_SERIAL="$serial"
snapshot_sources() {
  (
    cd "$run/source"
    find . -type f -not -path './android/.gradle/*' -not -path './android/app/build/*' \
      -not -path './android/build/*' -not -path './android/.kotlin/*' \
      -not -path './acceptance/*' -not -path './validation/*' -print0 |
      sort -z | xargs -0 sha256sum
  )
}
snapshot_sources > "$run/source-inputs.sha256"
date -u +%FT%TZ > "$run/started-utc.txt"
"$JAVA_HOME/bin/java" -version 2> "$run/java-version.txt"
"$ANDROID_HOME/emulator/emulator" -version > "$run/emulator-version.txt" 2>&1
cp "$lab/sdk-packages.txt" "$run/"
(
  exec 9>&-
  cd "$build_root"
  ./gradlew --no-daemon testDebugUnitTest lintDebug lintRelease \
    assembleDebug assembleDebugAndroidTest assembleRelease
) 2>&1 | tee "$run/gradle.log"
snapshot_sources > "$run/source-inputs-after.sha256"
cmp "$run/source-inputs.sha256" "$run/source-inputs-after.sha256" || die 'Sources changed during the build; evidence is invalid.'
sha256sum "$build_root/app/build/outputs/apk/debug/app-debug.apk" \
  "$build_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" \
  "$build_root/app/build/outputs/apk/release/app-release-unsigned.apk" > "$run/apks.sha256"
"$adb" -P 5038 start-server 9>&-
if "$adb" -P 5038 devices | awk '{print $1}' | grep -qx "$serial"; then
  die 'The selected emulator port is already occupied; refusing to control that device.'
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
  # setsid gives this script an owned process group; never target another emulator.
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
setsid "$ANDROID_HOME/emulator/emulator" -avd "collie-$profile" -port "$port" \
  -no-window -no-audio -no-snapshot -wipe-data -accel on -gpu swiftshader \
  > "$run/emulator.log" 2>&1 9>&- &
emulator_pid=$!
timeout 180 "$adb" -P 5038 -s "$serial" wait-for-device
booted=false
for _ in {1..120}; do
  if [[ $("$adb" -P 5038 -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then
    booted=true; break
  fi
  kill -0 "$emulator_pid" 2>/dev/null || die 'Owned emulator exited; inspect its log.'
  sleep 2
done
[[ "$booted" == true ]] || die 'Emulator did not finish booting.'
"$adb" -P 5038 -s "$serial" shell getprop > "$run/device-properties.txt"
[[ $("$adb" -P 5038 -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r') == "$expected_api" ]] || die 'Unexpected emulator API.'
"$adb" -P 5038 -s "$serial" install -r "$build_root/app/build/outputs/apk/debug/app-debug.apk"
"$adb" -P 5038 -s "$serial" install -r "$build_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
classes=com.lateapex.collie.ui.NativeLaunchSmokeTest,com.lateapex.collie.ui.SystemBarInsetsDeviceTest
classes+=,com.lateapex.collie.ui.AgentIconsDeviceTest,com.lateapex.collie.data.AndroidKeystoreConnectionStoreTest
timeout 900 "$adb" -P 5038 -s "$serial" shell am instrument -w -r -e class "$classes" \
  com.lateapex.collie.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$run/instrumentation.log"
grep -Eq '^OK \([1-9][0-9]* tests?\)' "$run/instrumentation.log" || die 'Instrumentation did not report a passing test run.'
date -u +%FT%TZ > "$run/finished-utc.txt"
echo "Baseline validation passed; evidence at $run. Interactive hardening acceptance remains separate."
