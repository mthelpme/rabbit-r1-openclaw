# R1 Lockscreen Tweaks

A small **LSPosed / Vector** (Xposed) module that centres the keyguard clock and date, hides the
lock icon, and hides the bouncer's emergency-call button.

Companion to [`apps/r1-lockscreen-overlay`](../r1-lockscreen-overlay), which handles everything
that *is* a resource value. This module handles the things that aren't.

## Why this can't be an overlay

An RRO can only replace resources; these three aren't resources:

- **Clock/date alignment** is `android:layout_alignParentStart="true"` — a literal in
  `keyguard_clock_switch.xml`, with no dimen or bool behind it.
- **The emergency button** needs `visibility="gone"` in `keyguard_emergency_carrier_area.xml`.
- **The lock icon's** visibility is driven by `LockIconViewController`, in code.

An overlay could only reach the first two by replacing whole layouts, which forces it to hard-code
SystemUI's numeric resource IDs (`0x7f0a0439`…). Those shift on every LineageOS build, and the
failure mode is a lockscreen that won't inflate. A hook fails soft; a bad layout fails hard. That
trade is why these live here instead.

## Hook targets were read off the device, not assumed

Method names came from `dexdump` on this device's own `SystemUI.apk`:

| Class | Declared methods hooked |
|-------|------------------------|
| `com.android.keyguard.KeyguardClockSwitch` | `onFinishInflate`, `updateStatusArea(boolean)` |
| `com.android.keyguard.LockIconView` | `updateIcon`, `updateColorAndBackgroundVisibility` |
| `com.android.keyguard.LockIconViewController` | `updateVisibility*` (R8-suffixed on this build, matched by prefix) |
| `com.android.keyguard.EmergencyButton` | `onFinishInflate` |
| `com.android.keyguard.EmergencyButtonController` | `updateEmergencyCallButton` |
| `com.android.keyguard.CarrierTextController` | `onViewAttached` |

### The declared-method rule

`hookDeclared()` refuses to hook anything a class does not itself declare. This is not pedantry:
`XposedHelpers.findAndHookMethod` walks up the hierarchy, so hooking `setVisibility` on
`LockIconView` — which doesn't declare it — resolves to `android.view.View.setVisibility` and
would hide **every view in SystemUI**. The helper checks `declaredMethods` first and logs a skip
instead.

## Safety design

- **Scope is `com.android.systemui` only** (`res/values/arrays.xml`), unlike r1-immersive which
  must reach every app.
- Every tweak is independently guarded; one failing never affects the others.
- No exception escapes into keyguard code. A keyguard that throws during inflation is a device you
  cannot unlock.
- Each tweak is a `const val` at the top of `LockscreenHook.kt` — flip one to `false` to drop it.

## Carrier text

The label is read at runtime from **`Settings.Global.r1_carrier_label`**, so it changes with one
command and no rebuild:

```sh
adb shell settings put global r1_carrier_label "openclaw"
```

`CARRIER_TEXT` in the source is only the fallback for when that setting is unset; `""` there (with
the setting unset) leaves the real carrier alone. That setting name was already present on the
device, so this follows the convention rather than inventing one.

This is here rather than in a Magisk module because **the telephony database cannot hold it**. The
label comes from the `siminfo` column `carrier_name`, and the framework re-derives that from the
SIM every time the phone process starts. The `name_source` column protects `display_name`, not
`carrier_name` — confirmed on device: after setting both and rebooting, `display_name` survived as
`openclaw` while `carrier_name` had reverted to the SIM's value.

`CarrierTextManager.postToCallback` looked like the right hook, and it is declared — but logging
showed it never fires on this build. Rather than keep chasing whichever producer is live, the hook
anchors on the view: a `TextWatcher` on the `CarrierText` puts the label back whenever anything
changes it. That is self-correcting and independent of the code path that won.

## Emergency button

`HIDE_EMERGENCY_BUTTON` removes the ability to dial emergency services from the lock screen
without unlocking the device. On a phone with a live SIM that is a real capability to give up. Set
the constant to `false` to keep it.

## Build & install

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Enable and scope it (Vector CLI, or the manager UI):

```sh
CLI=/data/adb/modules/zygisk_vector/cli
adb shell su -c "sh $CLI modules enable com.r1lockscreen"
adb shell su -c "sh $CLI scope set com.r1lockscreen com.android.systemui/0"
adb shell su -c "killall com.android.systemui"     # no reboot needed
```

## Recovery

`adbd` is independent of SystemUI, so even a broken keyguard leaves adb reachable — you do not
need to unlock the device to back this out:

```sh
adb shell su -c "sh /data/adb/modules/zygisk_vector/cli modules disable com.r1lockscreen"
adb shell su -c "killall com.android.systemui"
```

## Verified on device

LineageOS `21.0-20250621-UNOFFICIAL-arm64_bvN`, 480×640 @ density 220. After a cold boot, with
both overlays active: clock centre offset **+0px**, date **−1px**, `lock_icon` absent from the
view tree, `emergency_call_button` absent from the bouncer while `lockPatternView` is present.
(The bouncer blocks `screencap`, so it is verified with `uiautomator dump`.)
