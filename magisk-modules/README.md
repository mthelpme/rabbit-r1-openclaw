# Magisk modules

Small Magisk modules used by the R1 setup. Each folder is a ready-to-zip module (its files are the
module contents). Build the flashable zips and flash them in the Magisk app, then reboot.

| Module | What it does | Needed by |
|--------|--------------|-----------|
| `ptt-remap` | Remaps the R1 side-button scancode `116` → `KEYCODE_BUTTON_1` via a keylayout overlay. | **ClawPTT** (PTT capture) |
| `wheel-remap` | Remaps the scroll wheel (`och1970_holl_key`, scancodes `103`/`108`) → `VOLUME_UP`/`VOLUME_DOWN`, so the wheel controls volume with no background app. ClawPTT re-catches these to scroll in-app. | optional |
| `hide-statusbar` | Hides the status bar globally via `policy_control immersive.status`. (Android 14: launcher only — use **R1 Immersive** for all apps.) | optional |
| `hide-ime-navbar` | Removes the keyboard's IME nav-bar strip via a fabricated overlay. | optional |
| `lockscreen-overlay` | Two RROs retuning the keyguard for the 480×640 panel: SystemUI (smaller clock, tighter gutters) + framework-res (slimmer, recoloured pattern grid). Also hides lockscreen notifications. | **[R1 Lockscreen Overlay](../apps/r1-lockscreen-overlay)** |
| `statusbar-overlay` | SystemUI RRO that blanks the mobile data-type indicator (LTE / 5G / H+) while keeping the signal bars. | **[R1 Status Bar Overlay](../apps/r1-statusbar-overlay)** |
| `motor-sepolicy` | SELinux rules allowing the camera-motor sysfs node to be written. | **R1 Tools** |
| `app-widgets-enable` | Enables `AppWidgetManager` for apps that expect widget support. | optional |

## Build the zips
```sh
./build-all.sh          # writes dist/<module>.zip for each folder
```

`lockscreen-overlay` carries a built APK that is **not** committed (APKs are git-ignored).
Build it first, or the module will flash with an empty overlay directory:

```sh
apps/r1-lockscreen-overlay/build.sh
apps/r1-statusbar-overlay/build.sh
```
Then in the Magisk app: **Modules → Install from storage →** pick the zip → reboot.

## Notes
- These modify system behavior at boot. If one causes a boot loop, boot to Magisk **safe mode** or
  run `magisk --remove-modules` to disable all modules, then reboot.
- `ptt-remap` is the only module strictly required for ClawPTT's hardware button.
