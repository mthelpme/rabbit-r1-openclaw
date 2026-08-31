# R1 Lockscreen Overlay

Two **Runtime Resource Overlays (RROs)** that retune the keyguard for the R1. Resource-only — no
code, no Xposed, no hooks.

| Source | Builds | Targets | Changes |
|--------|--------|---------|---------|
| `systemui/`  | `R1Lockscreen.apk`  | `com.android.systemui` | Clock type sizes, clock gutters, indication-text margin, blanked unlock-hint strings |
| `framework/` | `R1LockPattern.apk` | `android` (framework-res) | Pattern dot size, trail width, dot colours |

They are deliberately **separate packages** so either can be disabled on its own — the framework
overlay is the riskier of the two.

## Panel reality

`tools/recon-lockscreen.sh` on the actual device (LineageOS `21.0-20250621-UNOFFICIAL-arm64_bvN`):

```
Physical size: 480x640      Physical density: 320      Override density: 220
→ 349dp x 465dp of usable space
```

Note this is **not** the R1's bare 240×282 panel — the GSI reports 480×640. Stock
`large_clock_text_size` is **150dp**, roughly a third of the screen height for the clock alone.
That is the thing this overlay exists to fix.

## Every value here was read off the device

All eleven overrides were confirmed against `dist/recon/*.resources.txt`, with defaults noted in
the XML comments. Two candidates were deliberately **left alone**:

- `status_view_margin_horizontal` — default is already `0dp`; overriding would *add* margin.
- `keyguard_large_clock_top_margin` — reads `16777156dp`, a resource-reference sentinel rather than
  a real dimension. Writing a literal there changes behaviour the default doesn't imply.

## Unlock hint strings

`res/values/strings.xml` blanks `keyguard_unlock` ("Swipe up to open") and `keyguard_unlock_press`
("Press the unlock icon to open"). The second is blanked because
[r1-lockscreen-tweaks](../r1-lockscreen-tweaks) hides the lock icon — leaving it would point at
something not on screen.

This blanks the two hint **strings** rather than hiding the indication view, because that view also
carries "Charged", "Wrong pattern, try again", and lockout warnings. Blanking the strings drops the
hints and keeps the feedback. With them gone, your own lock-screen message
(`Settings.Secure.lock_screen_owner_info`) shows in that slot instead of being cycled with them.

Not blanked, but available if you want them:

| String | Default | Where |
|--------|---------|-------|
| `keyguard_retry` | "Swipe up to try again" | SystemUI — after a failed biometric |
| `dismissible_keyguard_swipe` | "Swipe up to continue" | SystemUI |
| `lockscreen_storage_locked` | "Unlock for all features and data" | **framework-res** — only before the first unlock after a reboot, so it is a real state indicator rather than a recurring hint |

## Important: overlays cannot add resources

An RRO may only *replace* a resource the target already has, matched by exact **name and type**. It
cannot add new ones. Two consequences:

- **A name that doesn't exist in the target is silently ignored** — no error, no log, no effect.
  `build.sh` cross-checks every name against the recon dump and prints `ok` / `MISS` per entry, so
  dead overrides surface at build time instead of as a mystery.
- **The mascot cannot come from here.** There is no drawable to replace. It is the lock *wallpaper*
  instead — [`tools/make-lock-wallpaper.py`](../../tools/make-lock-wallpaper.py).

## Build

```sh
tools/recon-lockscreen.sh          # first, with the R1 attached — enables name validation
./build.sh                         # → ../../magisk-modules/lockscreen-overlay/system/product/overlay/
../../magisk-modules/build-all.sh  # → magisk-modules/dist/lockscreen-overlay.zip
```

Needs `ANDROID_HOME` (build-tools + `platforms/android-34`) and a JDK; `build.sh` finds Homebrew's
`openjdk@17` or Android Studio's bundled JBR automatically. It generates a throwaway signing key on
first run (git-ignored) — an overlay must be signed to parse, but since these ship from a system
partition the specific key doesn't matter.

Flash the zip in Magisk → Modules → Install from storage → reboot.

## Why Magisk rather than `adb install`

A non-static overlay is only trusted when it lives on a system partition. The Magisk module mounts
both APKs under `/system/product/overlay/` and `service.sh` enables them once boot completes — the
same pattern as [`hide-ime-navbar`](../../magisk-modules/hide-ime-navbar). Sideloading installs the
APK but leaves it un-enableable.

## Recovery

The SystemUI overlay can at worst cause a SystemUI restart. The **framework** overlay targets
`android`, so a bad one can boot-loop the device — that is why it is separable:

```sh
adb shell su -c "cmd overlay disable com.r1lockscreen.pattern"   # framework only
adb shell su -c "cmd overlay disable com.r1lockscreen.overlay"   # systemui only
adb shell su -c "magisk --remove-modules"                        # nuclear, then reboot
```

If the device won't boot far enough for adb, boot to Magisk safe mode — that disables all modules.
