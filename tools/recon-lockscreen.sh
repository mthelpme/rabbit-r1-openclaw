#!/usr/bin/env bash
# Dump everything we need to write a correct lockscreen overlay for THIS device.
#
# Overlays can only override resources that actually exist in the target package, with the
# exact name and type. Those names drift between Android releases and LineageOS builds, so
# guessing them yields an overlay that installs cleanly and does nothing. Run this with the
# R1 connected, then fill in the overlay from real names.
#
# Usage:  tools/recon-lockscreen.sh [outdir]      (default: dist/recon)
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${1:-dist/recon}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AAPT2="$(ls -d "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)"

command -v adb >/dev/null || { echo "adb not on PATH"; exit 1; }
[ -n "$(adb devices | sed '1d' | grep -w device || true)" ] || { echo "No device in 'device' state. Plug in the R1 and enable USB debugging."; exit 1; }
[ -n "$AAPT2" ] || { echo "aapt2 not found under $SDK/build-tools — set ANDROID_HOME."; exit 1; }

mkdir -p "$OUT"
echo "→ writing to $OUT/"

{
  echo "=== display ==="
  adb shell wm size; adb shell wm density
  echo
  echo "=== build ==="
  adb shell getprop ro.build.version.release
  adb shell getprop ro.build.version.sdk
  adb shell getprop ro.lineage.version
  echo
  echo "=== lockscreen settings (current values) ==="
  for k in lock_screen_show_notifications lock_screen_allow_private_notifications \
           lockscreen_quick_affordance_selections lock_screen_weather_enabled \
           lock_screen_custom_clock_face lockscreen_use_double_line_clock; do
    printf '  secure/%-42s = %s\n' "$k" "$(adb shell settings get secure "$k" 2>/dev/null | tr -d '\r')"
  done
  echo
  echo "=== existing overlays (SystemUI / framework) ==="
  adb shell cmd overlay list 2>/dev/null | grep -iE 'systemui|^\[|android$' | head -40
} > "$OUT/device.txt" 2>&1
echo "  device.txt"

# --- pull the two packages whose resources we may override -------------------
pull_and_dump() {
  local pkg="$1" label="$2"
  local remote; remote="$(adb shell pm path "$pkg" 2>/dev/null | sed -n 's/^package://p' | tr -d '\r' | head -1)"
  if [ -z "$remote" ]; then echo "  ! could not resolve $pkg"; return; fi
  adb pull "$remote" "$OUT/$label.apk" >/dev/null 2>&1 || { echo "  ! pull failed for $pkg"; return; }
  "$AAPT2" dump resources "$OUT/$label.apk" > "$OUT/$label.resources.txt" 2>/dev/null || true
  echo "  $label.apk  +  $label.resources.txt"
}
pull_and_dump com.android.systemui systemui
pull_and_dump android framework-res

# --- the names we actually care about ---------------------------------------
grep_res() {   # grep_res <file> <label> <pattern>
  local f="$1" label="$2" pat="$3"
  [ -f "$f" ] || return 0
  set +e
  echo "=== $label ==="
  # aapt2 prints "    resource 0x7f070324 dimen/large_clock_text_size" followed by an
  # indented default value line. Emit "type/name = default" for each match.
  awk -v pat="$pat" '
    /^ +resource 0x[0-9a-f]+ [a-z]+\/[A-Za-z0-9_]+$/ {
      split($3, a, "/"); type=a[1]; name=a[2]; pend=""
      if ((type ~ /^(dimen|color|bool|integer|style|layout)$/) && (name ~ pat)) pend=$3
      next_is=pend; next
    }
    next_is != "" && /^ +\(\)/ { v=$0; sub(/^ +\(\) */, "", v); printf "  %-46s = %s\n", next_is, v; next_is=""; next }
    { next_is="" }
  ' "$f" | sort -u
  echo
}
{
  grep_res "$OUT/systemui.resources.txt"      "SystemUI · clock"    'clock'
  grep_res "$OUT/systemui.resources.txt"      "SystemUI · keyguard" 'keyguard'
  grep_res "$OUT/systemui.resources.txt"      "SystemUI · lock"     'lock'
  grep_res "$OUT/framework-res.resources.txt" "framework · pattern" 'lock_pattern'
  set -e
} > "$OUT/candidates.txt" 2>&1
echo "  candidates.txt"

echo
echo "Done. Next:"
echo "  1. less $OUT/candidates.txt          # exact overridable resource names"
echo "  2. less $OUT/device.txt              # panel size for the wallpaper, current settings"
echo "  3. put the names you want into apps/r1-lockscreen-overlay/res/values/*.xml"
