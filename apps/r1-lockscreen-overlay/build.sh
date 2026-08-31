#!/usr/bin/env bash
# Build + sign the two lockscreen RROs and drop them into the Magisk module payload.
#
#   systemui/  -> R1Lockscreen.apk   (targets com.android.systemui — clock + gutters)
#   framework/ -> R1LockPattern.apk  (targets android — pattern grid geometry + colours)
#
# Output: magisk-modules/lockscreen-overlay/system/product/overlay/
# Then:   magisk-modules/build-all.sh  → dist/lockscreen-overlay.zip → flash in Magisk
set -euo pipefail
cd "$(dirname "$0")"
REPO="$(cd ../.. && pwd)"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$BT" ] || { echo "No build-tools under $SDK — set ANDROID_HOME."; exit 1; }
AAPT2="$BT/aapt2"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"
# Prefer the platform this repo targets (compileSdk 34 = Android 14); fall back to newest.
JAR="$SDK/platforms/android-34/android.jar"
[ -f "$JAR" ] || JAR="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$JAR" ] || { echo "No platform android.jar under $SDK/platforms."; exit 1; }

# --- JDK (keytool + apksigner both need a real JRE; macOS ships only a stub) ---
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

BUILD=build; OUTDIR="$REPO/magisk-modules/lockscreen-overlay/system/product/overlay"
KS="$BUILD/overlay.keystore"
rm -rf "$BUILD"; mkdir -p "$BUILD" "$OUTDIR"
echo "→ JDK $JAVA_HOME"
echo "→ SDK $(basename "$(dirname "$JAR")") / build-tools $(basename "${BT%/}")"

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias overlay -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=r1-lockscreen-overlay, OU=openclaw, O=openclaw, C=US" >/dev/null 2>&1
  echo "→ generated throwaway signing key (git-ignored)"
fi

# Overrides for resources the target doesn't have are silently ignored at runtime, so check
# every name against the on-device dump when one exists.
validate() {   # validate <srcdir> <dumpfile>
  local src="$1" dump="$2" miss=0
  if [ ! -f "$dump" ]; then
    echo "   (no $dump — run tools/recon-lockscreen.sh to enable name checking)"; return 0
  fi
  while IFS='|' read -r type name; do
    [ -z "$name" ] && continue
    if grep -qE "resource 0x[0-9a-f]+ $type/$name\$" "$dump"; then
      printf '   ok    %s/%s\n' "$type" "$name"
    else
      printf '   MISS  %s/%s — absent from this build, override will be ignored\n' "$type" "$name"; miss=1
    fi
  done < <(sed -nE 's/.*<(dimen|color|bool|integer|string)[[:space:]]+name="([^"]+)".*/\1|\2/p' "$src"/res/values/*.xml)
  return $miss
}

build_one() {  # build_one <srcdir> <outname> <dumpfile>
  local src="$1" out="$2" dump="$3"
  echo
  echo "── $out  (from $src/)"
  validate "$src" "$dump" || echo "   ↑ fix the MISS entries above before flashing."
  "$AAPT2" compile --dir "$src/res" -o "$BUILD/$src.zip"
  "$AAPT2" link -I "$JAR" --manifest "$src/AndroidManifest.xml" -o "$BUILD/$src-unsigned.apk" "$BUILD/$src.zip"
  "$ZIPALIGN" -f 4 "$BUILD/$src-unsigned.apk" "$BUILD/$src-aligned.apk"
  # v4 signing writes a sidecar .idsig that must NOT ship inside /system/product/overlay.
  "$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --v4-signing-enabled false --out "$OUTDIR/$out" "$BUILD/$src-aligned.apk"
  rm -f "$OUTDIR/$out.idsig"
  echo "   built $out"
}

build_one systemui  R1Lockscreen.apk  "$REPO/dist/recon/systemui.resources.txt"
build_one framework R1LockPattern.apk "$REPO/dist/recon/framework-res.resources.txt"

echo
echo "next:  magisk-modules/build-all.sh  →  flash dist/lockscreen-overlay.zip  →  reboot"
