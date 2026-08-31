#!/usr/bin/env bash
# Build + sign the status-bar RRO and drop it into the Magisk module payload.
#
#   systemui/ -> R1StatusBar.apk  (targets com.android.systemui — blanks the data-type indicator)
#
# Output: magisk-modules/statusbar-overlay/system/product/overlay/R1StatusBar.apk
# Then:   magisk-modules/build-all.sh  → dist/statusbar-overlay.zip → flash in Magisk
set -euo pipefail
cd "$(dirname "$0")"
REPO="$(cd ../.. && pwd)"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$BT" ] || { echo "No build-tools under $SDK — set ANDROID_HOME."; exit 1; }
AAPT2="$BT/aapt2"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"
JAR="$SDK/platforms/android-34/android.jar"
[ -f "$JAR" ] || JAR="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$JAR" ] || { echo "No platform android.jar under $SDK/platforms."; exit 1; }

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/keytool" ]; then
  for cand in \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/Cellar/openjdk@17/*/libexec/openjdk.jdk/Contents/Home \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    [ -x "$cand/bin/keytool" ] && { JAVA_HOME="$cand"; break; }
  done
fi
[ -x "${JAVA_HOME:-}/bin/keytool" ] || {
  echo "No JDK found. Install one (brew install openjdk@17) or set JAVA_HOME."; exit 1; }
export JAVA_HOME; PATH="$JAVA_HOME/bin:$PATH"; export PATH

BUILD=build; OUTDIR="$REPO/magisk-modules/statusbar-overlay/system/product/overlay"
KS="$BUILD/overlay.keystore"
rm -rf "$BUILD"; mkdir -p "$BUILD" "$OUTDIR"
echo "→ JDK $JAVA_HOME"

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias overlay -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=r1-statusbar-overlay, OU=openclaw, O=openclaw, C=US" >/dev/null 2>&1
  echo "→ generated throwaway signing key (git-ignored)"
fi

# A drawable the target doesn't have is silently ignored at runtime — check every filename
# against the on-device dump when one exists.
DUMP="$REPO/dist/recon/systemui.resources.txt"
if [ -f "$DUMP" ]; then
  echo "→ validating drawable names against the device dump"
  miss=0
  for f in systemui/res/drawable/*.xml; do
    n="$(basename "$f" .xml)"
    if grep -qE "resource 0x[0-9a-f]+ drawable/$n\$" "$DUMP"; then
      printf '   ok    drawable/%s\n' "$n"
    else
      printf '   MISS  drawable/%s — absent from this build, override will be ignored\n' "$n"; miss=1
    fi
  done
  [ "$miss" = 1 ] && echo "   ↑ fix these before flashing, or they are dead weight."
else
  echo "→ no device dump at dist/recon/ — run tools/recon-lockscreen.sh to enable name checking."
fi

echo "→ aapt2 compile + link"
"$AAPT2" compile --dir systemui/res -o "$BUILD/res.zip"
"$AAPT2" link -I "$JAR" --manifest systemui/AndroidManifest.xml -o "$BUILD/unsigned.apk" "$BUILD/res.zip"
"$ZIPALIGN" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
# v4 signing writes a sidecar .idsig that must NOT ship inside /system/product/overlay.
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v4-signing-enabled false --out "$OUTDIR/R1StatusBar.apk" "$BUILD/aligned.apk"
rm -f "$OUTDIR/R1StatusBar.apk.idsig"

echo "built $OUTDIR/R1StatusBar.apk"
echo "next:  magisk-modules/build-all.sh  →  flash dist/statusbar-overlay.zip  →  reboot"
