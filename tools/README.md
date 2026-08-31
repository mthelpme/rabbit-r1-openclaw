# tools

Host-side helpers. Neither needs root; `recon-lockscreen.sh` needs the R1 attached over adb.

| Script | What it does |
|--------|--------------|
| `make-lock-wallpaper.py` | Composes the openclaw mascot centred on a flat background at panel resolution → `dist/lock-wallpaper.png`. |
| `recon-lockscreen.sh` | Dumps this device's real SystemUI/framework resource names, panel size, and lockscreen settings → `dist/recon/`. |

---

## make-lock-wallpaper.py

The mascot on the lockscreen comes from the **lock wallpaper**, not from the SystemUI overlay — an
RRO can only replace resources that already exist in SystemUI, and there is no mascot drawable to
replace. The wallpaper route is also OTA-proof and needs no root.

```sh
python3 tools/make-lock-wallpaper.py                                  # 480x640, bg #14110D
python3 tools/make-lock-wallpaper.py --size 480x640 --scale 0.42      # confirm size: adb shell wm size
python3 tools/make-lock-wallpaper.py --bg '#000000' --offset-y -20    # blacker, mascot nudged up
```

Background defaults to `#14110D` — the same warm black as
[R1 Tools](../apps/r1-tools/app/src/main/java/com/r1motor/MainActivity.kt), so the lockscreen matches
the rest of the device. `--scale` is mascot height as a fraction of panel height.

> **Size matters here.** The R1's bare panel is 240×282, but under this GSI Android reports
> **480×640 @ density 220** (~349×465dp) — that is what the wallpaper must match. `wm size` is the
> authority, not the spec sheet.

**Install it** (the picker is the reliable route — there is no supported adb command to set the
*lock* wallpaper specifically):

```sh
adb push dist/lock-wallpaper.png /sdcard/Pictures/
```
Then on the R1: Files/Gallery → the image → **Set as wallpaper → Lock screen**. Choose "no crop" /
original size if offered, so the mascot stays centred.

> The pattern bouncer dims whatever is behind it, so the mascot shows at full strength on the idle
> face and dimmed behind the pattern grid. That's Android's scrim, not the wallpaper.

## recon-lockscreen.sh

Run this before trusting any resource name in
[`apps/r1-lockscreen-overlay/res/values/`](../apps/r1-lockscreen-overlay/res/values). Overlay
overrides for names that don't exist in your build are **silently ignored** — no error, no effect.

```sh
tools/recon-lockscreen.sh
less dist/recon/candidates.txt    # overridable clock / keyguard / lock / pattern resources
less dist/recon/device.txt        # panel size, density, current lockscreen settings
```

It pulls `SystemUI.apk` and `framework-res.apk` off the device and runs `aapt2 dump resources` on
each. Nothing is written to the device.

### What lives where

The pattern grid is `com.android.internal.widget.LockPatternView` — **framework-res**, not SystemUI.
Its dot geometry and colours can only be overridden by an overlay targeting `android`, which is
riskier than one targeting SystemUI: a bad framework overlay can boot-loop the device rather than
just restart SystemUI. That is why it ships as a second, separately-disableable overlay
(`R1LockPattern.apk`) rather than being folded into the SystemUI one.

Confirmed present on the R1 GSI (LineageOS 21.0-20250621):
`lock_pattern_dot_size` 14dp · `lock_pattern_dot_size_activated` 30dp ·
`lock_pattern_dot_line_width` 22dp · `lock_pattern_view_regular_color` #ffffffff ·
`lock_pattern_view_success_color` #ffffffff
