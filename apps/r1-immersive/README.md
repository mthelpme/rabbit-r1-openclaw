# R1 Immersive

A tiny **LSPosed / Vector** (Xposed) module that force-hides the system status bar in **every app**,
with swipe-from-top to reveal it transiently. On Android 14 this is the only reliable way to hide
the bar app-wide — `policy_control` immersive only takes on the launcher, and `cmd overlay
fabricate` can't zero the status-bar height (it rejects dimension types).

It hooks `android.app.Activity` (`onResume` / `onWindowFocusChanged`) and calls
`WindowInsetsController.hide(statusBars())` with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`.

## Requirements
- Magisk with **Zygisk** enabled + the **Vector** framework (maintained LSPosed successor).

## Build & install
```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Enable (important: scope to ALL apps)
Vector only injects a module into the processes it's **scoped** to, and the hook must run inside
each app — scoping to just "System Framework" hooks `system_server` only and does nothing visible.
Enable the module, then scope it to every app:
```sh
CLI=/data/adb/modules/zygisk_vector/cli
adb shell su -c "sh $CLI modules enable com.r1immersive"
# scope to all installed packages:
adb shell pm list packages | sed 's/^package://' | awk '{print $1"/0"}' \
  | xargs adb shell su -c "sh $CLI scope set com.r1immersive"
```
Reboot (or force-stop apps) so they load the module. Newly installed apps need a re-scope.

Uses the Xposed API (`libs/api-82.jar`, compile-only).
