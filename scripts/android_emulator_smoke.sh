#!/usr/bin/env bash
set -euo pipefail
diagnose() {
  echo '--- Android touch diagnostics ---'
  adb shell wm size || true
  adb shell dumpsys input | grep -E 'SurfaceOrientation|DisplayViewport' | head -20 || true
  adb logcat -d -s AKG_ANDROID_TOUCH:I AKG_ANDROID:E AKG_ANDROID_SMOKE:I || true
}
trap 'diagnose' ERR
APK=$(find apk -name '*.apk' -type f -print -quit);test -n "$APK"
adb install -r "$APK"
adb shell wm size 640x1280
PKG=com.bitvaultengine.retrocollection.preview
ACT=com.bitvaultengine.retrocollection.MainActivity
for GAME in 1TuDbnOzqjx3Z0WKKuc3zkRrLAR7F_pwf 1RW03OkSJ_ZC4fCc2fT84rtUGwqmng0dT; do
  adb shell am force-stop "$PKG";adb logcat -c
  adb shell am start -n "$PKG/$ACT" --es game_id "$GAME" --ez akg_ci_smoke true
  # Wait for the game screen to start; then exercise all four face buttons and right direction.
  for i in $(seq 1 60); do
    if adb logcat -d -s AKG_ANDROID:V | grep -q 'PLAYER_READY'; then break; fi
    if adb logcat -d -s AKG_ANDROID:E | grep -q 'Player failed'; then adb logcat -d -s AKG_ANDROID:E; exit 1; fi
    sleep 2
  done
  adb logcat -d -s AKG_ANDROID:V | grep -q 'PLAYER_READY'
  adb shell input tap 1152 448
  adb shell input tap 1024 512
  adb shell input tap 1024 384
  adb shell input tap 896 448
  adb shell input tap 235 461
  for i in $(seq 1 90); do
    if adb logcat -d -s AKG_ANDROID_SMOKE:I | grep -q 'PASS'; then break; fi
    if adb logcat -d -s AKG_ANDROID:E | grep -q 'Player failed'; then adb logcat -d -s AKG_ANDROID:E; exit 1; fi
    sleep 2
  done
  adb logcat -d -s AKG_ANDROID_SMOKE:I | grep 'PASS'
  for MASK in 256 1 512 2 128; do adb logcat -d -s AKG_ANDROID_TOUCH:I | grep -q "mask=$MASK"; done
  echo "PASS Android emulator touch input: A, B, X, Y and right direction"
done
