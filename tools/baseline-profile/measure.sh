#!/bin/bash
# Uso: measure.sh <apk> <etichetta>
set -u
ADB=~/Android/Sdk/platform-tools/adb
S=$(dirname "$0"); APK=$1; L=$2; PKG=com.mauztube
tap() { local xy; xy=$(python3 -I $S/ui.py $ADB "$1" ${2:-15}) || { echo "  [manca: $1]"; return 1; }; $ADB shell input tap $xy; }
jank() { $ADB shell dumpsys gfxinfo $PKG | grep -E "Total frames rendered|Janky frames:|50th percentile|90th percentile|95th percentile|99th percentile" | head -6 | sed 's/^/  /'; }

$ADB uninstall $PKG >/dev/null 2>&1
$ADB install -r "$APK" >/dev/null || exit 1
# Primo avvio (profileinstaller scrive i profili), poi compilazione come dopo qualche ora d'uso.
$ADB shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 6
$ADB shell am force-stop $PKG
$ADB shell cmd package compile -f -m speed-profile $PKG >/dev/null
echo "== $L"
for i in 1 2 3 4 5; do
  $ADB shell am force-stop $PKG; sleep 1
  $ADB shell am start -W -n $PKG/.MainActivity | grep TotalTime | awk '{print $2}'
done | sort -n | awk '{a[NR]=$1} END {print "  avvio a freddo (mediana di 5): " a[3] " ms   [min " a[1] ", max " a[5] "]"}'
sleep 3
tap "Continue without an account" 5 >/dev/null
sleep 1
# --- Scorrimento risultati di ricerca ---
tap "Search" 10 && sleep 1
$ADB shell input text "lofi%smusic"; $ADB shell input keyevent 66; sleep 2; $ADB shell dumpsys input_method | grep -q "mInputShown=true" && $ADB shell input keyevent 4
sleep 8
$ADB shell dumpsys gfxinfo $PKG reset >/dev/null
for i in $(seq 1 20); do $ADB shell input swipe 540 1900 540 500 120; sleep 0.6; done
echo "  -- scorrimento (20 swipe)"; jank
# --- Navigazione tra schede ---
$ADB shell input keyevent 4; sleep 2
$ADB shell dumpsys gfxinfo $PKG reset >/dev/null
for r in 1 2 3; do for t in Subscriptions Playlists History Settings Home; do tap "$t" 5 >/dev/null; sleep 0.8; done; done
echo "  -- navigazione (15 cambi scheda)"; jank
