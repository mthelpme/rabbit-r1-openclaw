#!/usr/bin/env bash
# Build + sign the status-bar RRO and drop it into the Magisk module payload.
#
#   systemui/ -> R1NightLight.apk  (targets com.android.systemui — blanks the data-type indicator)
#
# Output: magisk-modules/nightlight-overlay/system/product/overlay/R1NightLight.apk
# Then:   magisk-modules/build-all.sh  → dist/nightlight-overlay.zip → flash in Magisk
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

BUILD=build; OUTDIR="$REPO/magisk-modules/nightlight-overlay/system/product/overlay"
KS="$BUILD/overlay.keystore"
rm -rf "$BUILD"; mkdir -p "$BUILD" "$OUTDIR"
echo "→ JDK $JAVA_HOME"

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias overlay -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=r1-nightlight-overlay, OU=openclaw, O=openclaw, C=US" >/dev/null 2>&1
  echo "→ generated throwaway signing key (git-ignored)"
fi

# A drawable the target doesn't have is silently ignored at runtime — check every filename
# against the on-device dump when one exists.
DUMP="$REPO/dist/recon/framework-res.resources.txt"
if [ -f "$DUMP" ]; then
  echo "→ validating override names against the device dump"
  miss=0
  while IFS='|' read -r type name; do
    [ -z "$name" ] && continue
    if grep -qE "resource 0x[0-9a-f]+ $type/$name\$" "$DUMP"; then
      printf '   ok    %s/%s\n' "$type" "$name"
    else
      printf '   MISS  %s/%s — absent from this build, override will be ignored\n' "$type" "$name"; miss=1
    fi
  done < <(sed -nE 's/.*<(integer|bool|dimen|color)[[:space:]]+name="([^"]+)".*/\1|\2/p' framework/res/values/*.xml)
  [ "$miss" = 1 ] && echo "   ↑ fix these before flashing, or they are dead weight."
else
  echo "→ no device dump at dist/recon/ — run tools/recon-lockscreen.sh to enable name checking."
fi

echo "→ aapt2 compile + link"
"$AAPT2" compile --dir framework/res -o "$BUILD/res.zip"
"$AAPT2" link -I "$JAR" --manifest framework/AndroidManifest.xml -o "$BUILD/unsigned.apk" "$BUILD/res.zip"
"$ZIPALIGN" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
# v4 signing writes a sidecar .idsig that must NOT ship inside /system/product/overlay.
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v4-signing-enabled false --out "$OUTDIR/R1NightLight.apk" "$BUILD/aligned.apk"
rm -f "$OUTDIR/R1NightLight.apk.idsig"

echo "built $OUTDIR/R1NightLight.apk"
echo "next:  magisk-modules/build-all.sh  →  flash dist/nightlight-overlay.zip  →  reboot"
