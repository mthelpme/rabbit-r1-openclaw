# Magisk modules

Small Magisk modules used by the R1 setup. Each folder is a ready-to-zip module (its files are the
module contents). Build the flashable zips and flash them in the Magisk app, then reboot.

| Module | What it does | Needed by |
|--------|--------------|-----------|
| `ptt-remap` | Remaps the R1 side-button scancode `116` → `KEYCODE_BUTTON_1` via a keylayout overlay. | **ClawPTT** (PTT capture) |
| `hide-statusbar` | Hides the status bar globally via `policy_control immersive.status`. (Android 14: launcher only — use **R1 Immersive** for all apps.) | optional |
| `hide-ime-navbar` | Removes the keyboard's IME nav-bar strip via a fabricated overlay. | optional |
| `motor-sepolicy` | SELinux rules allowing the camera-motor sysfs node to be written. | **R1 Tools** |
| `app-widgets-enable` | Enables `AppWidgetManager` for apps that expect widget support. | optional |

## Build the zips
```sh
./build-all.sh          # writes dist/<module>.zip for each folder
```
Then in the Magisk app: **Modules → Install from storage →** pick the zip → reboot.

## Notes
- These modify system behavior at boot. If one causes a boot loop, boot to Magisk **safe mode** or
  run `magisk --remove-modules` to disable all modules, then reboot.
- `ptt-remap` is the only module strictly required for ClawPTT's hardware button.
