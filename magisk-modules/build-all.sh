#!/bin/bash
# Zip each Magisk module folder into dist/<module>.zip (flashable in the Magisk app).
set -e
cd "$(dirname "$0")"
mkdir -p dist
for d in */ ; do
  name="${d%/}"
  [ "$name" = "dist" ] && continue
  [ -f "$name/module.prop" ] || continue
  # zip appends to an existing archive, so stale entries survive a rebuild — remove first.
  rm -f "dist/$name.zip"
  ( cd "$name" && zip -qr -X "../dist/$name.zip" . -x '.*' -x '*/.*' -x '**/.DS_Store' )
  echo "built dist/$name.zip"
done
echo "Done. Flash a zip in Magisk → Modules → Install from storage, then reboot."
