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
  adb shell input swipe 1152 448 1152 448 220
  adb shell input swipe 1024 512 1024 512 220
  adb shell input swipe 1024 384 1024 384 220
  adb shell input swipe 896 448 896 448 220
  adb shell input swipe 192 461 240 461 220
  for i in $(seq 1 90); do
    if adb logcat -d -s AKG_ANDROID_SMOKE:I | grep -q 'PASS'; then break; fi
    if adb logcat -d -s AKG_ANDROID:E | grep -q 'Player failed'; then adb logcat -d -s AKG_ANDROID:E; exit 1; fi
    sleep 2
  done
  adb logcat -d -s AKG_ANDROID_SMOKE:I | grep 'PASS'
  for FACE in 1 2 4 8; do adb logcat -d -s AKG_ANDROID_TOUCH:I | grep -q "faces=$FACE"; done
  for MASK in 256 1 128; do adb logcat -d -s AKG_ANDROID_INPUT:I | tr -d '\r' | grep -q "core=$MASK$"; done
  if [ "$GAME" = 1RW03OkSJ_ZC4fCc2fT84rtUGwqmng0dT ]; then
    for MASK in 512 2; do adb logcat -d -s AKG_ANDROID_INPUT:I | tr -d '\r' | grep -q "core=$MASK$"; done
  fi
  echo "PASS four touch buttons and free-joystick direction reached core; NES extra buttons repeat A and B"
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

# The library Settings button exposes all four button controls, and the full
# game list remains navigable and searchable (not just the first screen).
adb shell am force-stop "$PKG"
adb logcat -c
adb shell am start -n "$PKG/$ACT"
python3 - <<'PYUI'
import subprocess,time,re,xml.etree.ElementTree as ET

def adb(*args):return subprocess.check_output(['adb',*args],timeout=35).decode('utf8','replace')
def hierarchy():
 adb('shell','uiautomator','dump','/sdcard/akg-library.xml');return ET.fromstring(adb('exec-out','cat','/sdcard/akg-library.xml'))
def find_text(text):
 for _ in range(12):
  try:
   root=hierarchy();n=next((n for n in root.iter('node') if n.get('text','').casefold()==text.casefold()),None)
   if n is not None:return n
  except Exception:pass
  time.sleep(.5)
 raise RuntimeError('Missing UI text: '+text)
def tap(n):
 b=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.get('bounds',''))
 if not b:raise RuntimeError('Bad bounds for '+repr(n.attrib))
 x1,y1,x2,y2=map(int,b.groups());adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
find_text('97 of 97 games  •  scroll to see all')
root=hierarchy()
if not any(n.get('class')=='android.widget.GridView' and n.get('scrollable')=='true' for n in root.iter('node')):raise RuntimeError('Library is not scrollable')
for _ in range(25):
 root=hierarchy()
 if any(n.get('text','').startswith('Zelda II - The Adventure of Link') for n in root.iter('node')):break
 adb('shell','input','swipe','900','550','900','180','120')
else:raise RuntimeError('Last indexed game not visible after scrolling')
print('PASS library count and scrolling to last indexed game')
tap(find_text('Settings'));tap(find_text('Button mapping: classic games'));find_text('A button: A');find_text('B button: B');tap(find_text('X button: Turbo A'));tap(find_text('Turbo A'));find_text('Y button: Turbo B');tap(find_text('Done'));find_text('Move joystick and four buttons');find_text('Button size');tap(find_text('Close'))
print('PASS main Settings exposes movement, four-button mapping, NES turbo defaults and button size')
# Exercise the real main-menu position editor for all four buttons.
tap(find_text('Settings'));tap(find_text('Move joystick and four buttons'))
for _ in range(45):
 if 'PLAYER_READY' in adb('logcat','-d','-s','AKG_ANDROID:V'):break
 time.sleep(1)
else:raise RuntimeError('Control layout editor did not start')
for x1,y1,x2,y2 in [(1152,448,1150,420),(1024,512,1030,535),(1024,384,1025,355),(896,448,890,470)]:
 adb('shell','input','swipe',str(x1),str(y1),str(x2),str(y2),'500')
adb('shell','input','tap','640','50');find_text('Settings')
xml=ET.fromstring(adb('shell','run-as','com.bitvaultengine.retrocollection.preview','cat','shared_prefs/controls.xml'));vals={n.get('name'):n.get('value') for n in xml}
for i,(x,y) in enumerate([(1150,420),(1030,535),(1025,355),(890,470)],1):
 if abs(float(vals.get('x'+str(i),'nan'))-x/1280)>.02 or abs(float(vals.get('y'+str(i),'nan'))-y/640)>.02:raise RuntimeError('Button '+str(i)+' position not saved: '+str(vals))
print('PASS all four button positions moved and persisted from main Settings')
tap(find_text('Settings'));tap(find_text('Reset control positions'))
root=hierarchy();entry=next((n for n in root.iter('node') if n.get('class')=='android.widget.EditText'),None)
if entry is None:raise RuntimeError('Search field missing')
tap(entry);adb('shell','input','text','Zelda');find_text('2 of 97 games  •  scroll to see all');find_text('Zelda II - The Adventure of Link (USA)')
print('PASS searchable final game from 97-file library')
PYUI

# Exercise moving a real control through the settings dialog and check persisted positions.
adb shell am force-stop "$PKG"
adb logcat -c
adb shell am start -n "$PKG/$ACT" --es game_id 1TuDbnOzqjx3Z0WKKuc3zkRrLAR7F_pwf --ez akg_ci_smoke true
python3 - <<'PY'
import subprocess,time,re,xml.etree.ElementTree as ET
pkg='com.bitvaultengine.retrocollection.preview'
def adb(*args):return subprocess.check_output(['adb',*args],timeout=35).decode('utf-8','replace')
for _ in range(45):
 if 'PLAYER_READY' in adb('logcat','-d','-s','AKG_ANDROID:V'):break
 time.sleep(1)
else:raise SystemExit('FAIL: game did not become ready for control-position test')
adb('shell','input','tap','640','50')
move=None
for _ in range(8):
 try:
  adb('shell','uiautomator','dump','/sdcard/akg-settings.xml');root=ET.fromstring(adb('exec-out','cat','/sdcard/akg-settings.xml'))
  move=next((n for n in root.iter('node') if n.get('text')=='Move controls'),None)
  if move is not None:break
 except Exception:pass
 time.sleep(1)
if move is None:raise SystemExit('FAIL: Move controls setting not found')
b=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',move.get('bounds',''))
if not b:raise SystemExit('FAIL: move setting bounds unavailable')
x1,y1,x2,y2=map(int,b.groups());adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
adb('shell','input','swipe','1152','448','1075','360','800')
adb('shell','input','tap','640','50')
for _ in range(10):
 try:
  xml=ET.fromstring(adb('shell','run-as',pkg,'cat','shared_prefs/controls.xml'));vals={n.get('name'):n.get('value') for n in xml}
  x=float(vals.get('x1','nan'));y=float(vals.get('y1','nan'))
  if .82<x<.86 and .54<y<.58:
   print('PASS Android control movement persisted: A position');break
 except Exception:pass
 time.sleep(1)
else:raise SystemExit('FAIL: moved A position was not persisted')
PY

# Verify the in-game settings can save and restore a chosen manual stage/position slot.
adb shell am force-stop "$PKG"
adb logcat -c
adb shell am start -n "$PKG/$ACT" --es game_id 1TuDbnOzqjx3Z0WKKuc3zkRrLAR7F_pwf --ez akg_ci_smoke true
python3 - <<'PY'
import subprocess,time,re,xml.etree.ElementTree as ET
def adb(*args):return subprocess.check_output(['adb',*args],timeout=35).decode('utf-8','replace')
def wait_log(tag,text):
 for _ in range(30):
  if text in adb('logcat','-d','-s',tag+':I'):return
  time.sleep(.5)
 raise RuntimeError('Missing expected '+tag+' event: '+text)
def click(text):
 for _ in range(8):
  adb('shell','uiautomator','dump','/sdcard/akg-slot.xml');root=ET.fromstring(adb('exec-out','cat','/sdcard/akg-slot.xml'))
  node=next((n for n in root.iter('node') if n.get('text','').casefold()==text.casefold()),None)
  if node is not None:
   b=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',node.get('bounds',''))
   if b:
    x1,y1,x2,y2=map(int,b.groups());adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));return
  time.sleep(.5)
 raise RuntimeError('Settings option missing: '+text)
wait_log('AKG_ANDROID','PLAYER_READY')
adb('shell','input','tap','640','50');click('Save stage / position');click('Slot 2');wait_log('AKG_ANDROID_STATE','Saved slot 2')
adb('shell','input','tap','640','50');click('Load stage / position');click('Slot 2');wait_log('AKG_ANDROID_STATE','Loaded slot 2')
print('PASS Android manual stage/position slot 2 save and restore')
PY

# Confirm the one exact-ROM memory-tested cheat is applied and reset by the SNES core.
adb shell am force-stop "$PKG"
adb logcat -c
adb shell am start -n "$PKG/$ACT" --es game_id 1RW03OkSJ_ZC4fCc2fT84rtUGwqmng0dT --ez akg_ci_smoke true
python3 - <<'PY'
import subprocess,time,re,xml.etree.ElementTree as ET
def adb(*args):return subprocess.check_output(['adb',*args],timeout=35).decode('utf8','replace')
def wait(tag,text):
 for _ in range(30):
  if text in adb('logcat','-d','-s',tag+':I'):return
  time.sleep(.5)
 raise RuntimeError('Expected event missing: '+text)
def click(text):
 for _ in range(8):
  adb('shell','uiautomator','dump','/sdcard/akg-cheat.xml');root=ET.fromstring(adb('exec-out','cat','/sdcard/akg-cheat.xml'))
  node=next((n for n in root.iter('node') if n.get('text','').casefold()==text.casefold()),None)
  if node is not None:
   b=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',node.get('bounds',''))
   if b:
    x1,y1,x2,y2=map(int,b.groups());adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));return
  time.sleep(.5)
 raise RuntimeError('UI option missing: '+text+'; visible='+repr([n.get('text','') for n in root.iter('node') if n.get('text')]))
wait('AKG_ANDROID','PLAYER_READY')
adb('shell','input','tap','640','50');click('Cheats');click('Lives counter 99 (memory tested)');click('Apply');wait('AKG_ANDROID_CHEAT','SMW cheat enforced')
adb('logcat','-c')
adb('shell','input','tap','640','50');click('Cheats');click('Lives counter 99 (memory tested)');click('Apply');wait('AKG_ANDROID_CHEAT','SMW cheat disabled')
print('PASS Android SNES exact-ROM cheat memory enforcement and reset')
PY

# Validate production verifier using a non-secret test key, including tampering and rollback rejection.
adb shell am force-stop "$PKG"
adb logcat -c
adb shell am start -n "$PKG/$ACT" --ez akg_update_ci_self_test true
for i in $(seq 1 20); do
  if adb logcat -d -s AKG_UPDATE_TEST:I | grep -q 'PASS signature, tampering and version checks'; then break; fi
  if adb logcat -d -s AKG_UPDATE_TEST:E | grep -q 'FAIL update self test'; then adb logcat -d -s AKG_UPDATE_TEST:E; exit 1; fi
  sleep 1
done
adb logcat -d -s AKG_UPDATE_TEST:I | grep 'PASS signature, tampering and version checks'
echo 'PASS Android signed update verification self-test'

# Launch every packaged game on Android, not just the two interactive smoke tests.
adb shell am force-stop "$PKG"
adb logcat -c
adb shell am start -n "$PKG/$ACT" --ez akg_ci_catalog_test true
for i in $(seq 1 600); do
 if adb logcat -d -s AKG_ANDROID_CATALOG:I | grep -q 'PASS all '; then break; fi
 if adb logcat -d -s AKG_ANDROID_CATALOG:E | grep -q 'FAIL'; then adb logcat -d -s AKG_ANDROID_CATALOG:E; exit 1; fi
 sleep 2
done
adb logcat -d -s AKG_ANDROID_CATALOG:I | grep 'PASS all '
echo 'PASS all Android game files produced video and audio during startup'

# A dedicated non-debug certificate must permit an in-place APK version upgrade.
adb shell am force-stop "$PKG"
adb uninstall "$PKG"
PRIOR=$(find prior-signed-apk -name '*.apk' -type f -print -quit)
SIGNED=$(find signed-apk -name '*.apk' -type f -print -quit)
NEXT=$(find signed-apk-test -name '*.apk' -type f -print -quit)
test -n "$PRIOR" && test -n "$SIGNED" && test -n "$NEXT"
adb install "$PRIOR"
adb shell dumpsys package "$PKG" | grep -q "versionCode=2"
adb install -r "$SIGNED"
echo "PASS previous public signed APK version 2 upgrades in-place to current signed APK"
BASE_VERSION=$(python3 -c 'import json; print(json.load(open("updates/version.json"))["version_code"])')
adb shell dumpsys package "$PKG" | grep -q "versionCode=$BASE_VERSION"
adb install -r "$NEXT"
NEXT_VERSION=$((BASE_VERSION + 1))
adb shell dumpsys package "$PKG" | grep -q "versionCode=$NEXT_VERSION"
adb shell am start -n "$PKG/$ACT"
echo "PASS Android APK same-certificate in-place upgrade from version $BASE_VERSION to $NEXT_VERSION"


