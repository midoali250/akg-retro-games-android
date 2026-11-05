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

# Verify the actual library About button and the requested attribution dialog.
adb shell am force-stop "$PKG"
adb shell am start -n "$PKG/$ACT"
python3 - <<'PY'
import subprocess,time,re,xml.etree.ElementTree as ET
expected = 'This application has been developed by Ahmed Kamal Elgandy to serve as a digital archive and platform for running and optimizing classic retro and Atari-style games on mobile devices. The project aims to revive memories and preserve the software heritage of classic games with a smooth, lightweight experience, while fully respecting the intellectual property rights of the original production companies.'
def hierarchy():
 subprocess.run(['adb','shell','uiautomator','dump','/sdcard/akg-window.xml'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True,timeout=30)
 return ET.fromstring(subprocess.check_output(['adb','exec-out','cat','/sdcard/akg-window.xml'],timeout=15))
button=None
for attempt in range(12):
 try:
  root=hierarchy();button=next((n for n in root.iter('node') if n.get('text')=='About' and n.get('clickable')=='true'),None)
  if button is not None:break
 except Exception:pass
 time.sleep(2)
if button is None:raise SystemExit('FAIL: About button not found')
bounds=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',button.get('bounds',''))
if not bounds:raise SystemExit('FAIL: About button bounds missing')
x1,y1,x2,y2=map(int,bounds.groups());subprocess.run(['adb','shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)],check=True)
for attempt in range(8):
 try:
  root=hierarchy();texts=[n.get('text','') for n in root.iter('node')]
  if any(expected in text for text in texts):
   print('PASS Android About button and exact requested description');break
 except Exception:pass
 time.sleep(2)
else:raise SystemExit('FAIL: About description not found or differs')
PY
