#!/bin/bash
# Registra il profilo d'uso reale dell'app (build "profiling", non offuscata).
set -u
ADB=~/Android/Sdk/platform-tools/adb
S=$(dirname "$0"); APK=$1; OUT=$2; PKG=com.mauztube
tap() { local xy; xy=$(python3 -I $S/ui.py $ADB "$1" ${2:-15}) || { echo "  [manca: $1]"; return 1; }; $ADB shell input tap $xy; }
$ADB root >/dev/null; sleep 2
$ADB uninstall $PKG >/dev/null 2>&1
$ADB install -r "$APK" >/dev/null || exit 1
$ADB shell cmd package compile -f -m verify $PKG >/dev/null   # niente AOT: tutto passa dal profilatore JIT
for round in 1 2 3; do
  $ADB shell am force-stop $PKG; sleep 1
  $ADB shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 4
  tap "Continue without an account" 4 >/dev/null
  for t in Subscriptions Playlists History Settings; do tap "$t" 6 >/dev/null; sleep 1.5; $ADB shell input swipe 540 1700 540 700 150; sleep 1; done
  tap "Home" 6 >/dev/null; sleep 1
  tap "Search" 10 && sleep 1
  $ADB shell input text "lofi%smusic"; $ADB shell input keyevent 66; sleep 2; $ADB shell dumpsys input_method | grep -q "mInputShown=true" && $ADB shell input keyevent 4; sleep 8
  for i in $(seq 1 10); do $ADB shell input swipe 540 1900 540 500 120; sleep 0.5; done
  for i in $(seq 1 10); do $ADB shell input swipe 540 600 540 1900 120; sleep 0.4; done
  $ADB shell input tap 540 700; sleep 15                                   # apre il primo video e riproduce
  $ADB shell input tap 540 400; sleep 1                                    # mostra i controlli
  $ADB shell input swipe 540 1600 540 900 200; sleep 1                     # scorre i dettagli
  $ADB shell input keyevent 4; sleep 2                                     # riduce nel mini player
  $ADB shell input keyevent 4; sleep 1
  for t in Subscriptions Playlists History Settings Home; do tap "$t" 5 >/dev/null; sleep 1; done
  $ADB shell input swipe 540 1600 540 600 150; sleep 1
done
$ADB shell am force-stop $PKG; sleep 2
$ADB shell pm dump-profiles --dump-classes-and-methods $PKG
sleep 2
$ADB shell cat /data/misc/profman/$PKG-primary.prof.txt > "$OUT"
wc -l "$OUT"
