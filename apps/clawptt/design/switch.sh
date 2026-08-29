#!/bin/bash
# Swap the openclaw visual identity between the original 'legacy' palette and the
# official 'brand' palette (Claw Red / Deep Shell / Reef Teal / Space Dark).
# Swaps: colors + mascot + launcher icon. (Fonts are Figtree in both; the brand just
# bolds the headers — that lives in code and isn't toggled here.)
#
# Usage:  design/switch.sh legacy    # roll back to the old terracotta/sage look
#         design/switch.sh brand     # apply the official brand look
# Then rebuild:  ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
RES="$DIR/../app/src/main/res"
V="$1"
case "$V" in
  legacy|brand) ;;
  *) echo "usage: $(basename "$0") legacy|brand"; exit 1 ;;
esac
SRC="$DIR/$V"
cp "$SRC/colors.xml"                 "$RES/values/colors.xml"
cp "$SRC/mascot.png"                 "$RES/drawable-nodpi/mascot.png"
cp "$SRC/ic_launcher_foreground.xml" "$RES/drawable/ic_launcher_foreground.xml"
cp "$SRC/ic_launcher_background.xml" "$RES/drawable/ic_launcher_background.xml"
echo "Applied '$V' design."
echo "Rebuild + install:  ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk"
