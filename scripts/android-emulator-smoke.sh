#!/usr/bin/env bash
# Runs instrumentation tests on the booted emulator, then verifies clean install,
# upgrade install (-r), multi-activity launch, and absence of fatal logcat crashes
# on the signed release APK.
set -euo pipefail

(cd android && bash ./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace)

if [[ -z "${RELEASE_APK:-}" ]]; then
  mapfile -t apks < <(find . -maxdepth 1 -type f -name 'DastYab-*-android.apk' -print)
  if [[ ${#apks[@]} -ne 1 ]]; then
    echo "FAIL: Expected RELEASE_APK env var or exactly one ./DastYab-*-android.apk, found ${#apks[@]}:" >&2
    printf '%s\n' "${apks[@]}" >&2
    exit 1
  fi
  RELEASE_APK="${apks[0]}"
fi

test -n "${RELEASE_APK:-}" || {
  echo "FAIL: RELEASE_APK is not set" >&2
  exit 1
}
[[ "$RELEASE_APK" == *.apk ]] || {
  echo "FAIL: Not an APK filename: $RELEASE_APK" >&2
  exit 1
}
test -f "$RELEASE_APK" || {
  echo "FAIL: APK file does not exist: $RELEASE_APK" >&2
  exit 1
}

echo "Waiting for emulator device readiness..."
adb wait-for-device

echo "Testing release APK install & upgrade: $RELEASE_APK"
adb uninstall com.dastyab.app >/dev/null 2>&1 || true
adb install "$RELEASE_APK"
adb install -r "$RELEASE_APK"

adb logcat -c
for act in MainActivity BlackoutRescueActivity VpnLayersActivity NetworkToolboxActivity; do
  adb shell am start -W -n "com.dastyab.app/.${act}"
  sleep 2
done

if adb logcat -d | grep -aE "FATAL EXCEPTION|Process: com\.dastyab\.app.*AndroidRuntime"; then
  echo "FATAL crash detected in release APK smoke test!" >&2
  adb logcat -d -s AndroidRuntime:E DastyabLogger:E CrashHandler:E
  exit 1
fi

echo "✅ PASS: Instrumentation tests, clean install, upgrade install (-r), and release APK activity launches succeeded with zero crashes."
