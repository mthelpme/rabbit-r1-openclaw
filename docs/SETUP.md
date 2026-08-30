# Setup guide

End-to-end setup for the R1 openclaw toolkit. This assumes comfort with `adb`, `fastboot`,
Magisk, and the command line. **Read [CAVEATS.md](CAVEATS.md) first** — flashing can brick the
device and none of this is official.

Build host used throughout: macOS with the Android command-line tools + JDK 17. Adjust paths for
your machine. Every Android project here is self-contained (`./gradlew` per app); set
`ANDROID_HOME`/`JAVA_HOME` or create a `local.properties` with `sdk.dir=…` (git-ignored).

> **Downloads:** direct links for the GSI, Magisk, mtkclient, Vector, microG, Tailscale, etc. are
> in the [README Downloads table](../README.md#downloads-everything-youll-need).

---

## 1. Unlock the bootloader & recover a bricked R1 (MT6765)

The R1 is a MediaTek MT6765. Use [`mtkclient`](https://github.com/bkerler/mtkclient) over the
BROM/preloader interface to unlock the bootloader and to restore/patch partitions if you brick it.

- **dm-verity boot loop:** the reliable fix is not restoring stock — it's flashing a **vbmeta with
  verification disabled** (AVB flags `0x3` = `HASHTREE_DISABLED | VERIFICATION_DISABLED`). Flash
  the disabled-verity `vbmeta` and the loop clears.

## 2. LineageOS 21 (Android 14) GSI + microG + Magisk

1. The R1 supports **Treble** — flash an **arm64 A/B (`arm64_bvN`) LineageOS 21 GSI** to the
   `system` partition (fastbootd / dynamic super partition). Grab a recent
   `lineage-21.0-*-arm64_bvN.img.gz` from
   [Andy Yan's GSI builds](https://sourceforge.net/projects/andyyan-gsi/files/lineage-21-td/).
2. Keep the **verification-disabled `vbmeta`** so the GSI boots.
3. Add **microG** (de-Googled push/location) and **Magisk** (root). In Magisk, **enable Zygisk**
   (needed later for the Xposed framework) and reboot.

## 3. PTT key remap (required for ClawPTT)

The R1's side button reports scancode `116`. ClawPTT listens for `KEYCODE_BUTTON_1`, so a keylayout
Magisk module remaps it. Flash [`magisk-modules/ptt-remap`](../magisk-modules/ptt-remap) and reboot.
(If you use another key-remapper app, make sure it isn't *also* consuming `BUTTON_1`.)

## 4. Other Magisk modules (optional polish)

Flash any of these from [`magisk-modules/`](../magisk-modules) (build zips with
`magisk-modules/build-all.sh`, then flash in the Magisk app → reboot):

- **hide-statusbar** — hides the status bar globally via `policy_control` (note: on Android 14 this
  only reliably hides it on the launcher; for *all apps* use **R1 Immersive**, step 6).
- **hide-ime-navbar** — removes the keyboard's IME nav-bar strip.
- **motor-sepolicy** — SELinux rules so **R1 Tools** can write the camera-motor sysfs node.
- **app-widgets-enable** — makes `AppWidgetManager` work for apps that expect widgets.

## 5. Build & install the apps

**ClawPTT** lives in its own repository — [github.com/mthelpme/clawptt](https://github.com/mthelpme/clawptt).
Grab the APK from its [Releases](https://github.com/mthelpme/clawptt/releases), or build it from
source alongside the two R1 helper apps here:

```sh
export JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/android-sdk

# ClawPTT (separate repo)
git clone https://github.com/mthelpme/clawptt
( cd clawptt && ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk )

# R1 helper apps (this repo)
for app in apps/r1-immersive apps/r1-tools; do
  ( cd "$app" && ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk )
done
```

- **ClawPTT**: enable its **accessibility service** (Settings → Accessibility) — that's how it
  captures the side button globally, including on the lock screen.
- **R1 Tools**: enable the camera-motor / auto-rotate tiles or toggles you want.

## 6. Status bar hidden in *all* apps — R1 Immersive on LSPosed/Vector

Android 14 won't let a settings tweak or a plain overlay force the status bar off inside apps — it
needs a framework hook (Xposed). LSPosed proper is archived; use its maintained successor
**Vector** by JingMatrix.

1. Ensure **Zygisk** is on (step 2). Flash the **Vector** Zygisk module (from JingMatrix/Vector
   releases) in Magisk and reboot. Verify the `vectord` daemon is running.
2. Open the Vector manager (LSPosed-style — it hijacks `com.android.shell`; the module's "action"
   in Magisk launches it) and enable **R1 Immersive**.
3. **Scope it to *all apps***, not just "System Framework" — Vector only injects a module into the
   processes it's scoped to, and the hook lives in `android.app.Activity` inside each app. You can
   do this from the manager UI or the Vector CLI
   (`sh /data/adb/modules/zygisk_vector/cli scope set com.r1immersive <pkg>/0 …`).
4. Reboot (or force-stop apps) so they pick up the module. Swipe down from the top edge to reveal
   the bar transiently.

See [`apps/r1-immersive`](../apps/r1-immersive) for details.

## 7. (Optional) Self-hosted STT / TTS

On-device STT (Vosk) and TTS (SherpaTTS) work offline but are slower. For snappier, higher-quality
speech, run the bundled services on your server and expose them to the R1 over **Tailscale** (no
public ports):

- [`services/stt-service`](../services/stt-service) — faster-whisper, OpenAI-compatible
  `/v1/audio/transcriptions`, its own bearer token.
- [`services/tts-service`](../services/tts-service) — Kokoro, OpenAI-compatible
  `/v1/audio/speech` (streaming PCM), its own bearer token.

Each has a README with `uvicorn` run + `tailscale serve` exposure + a curl test.

## 8. Configure ClawPTT

In ClawPTT settings:
- **Gateway**: your OpenAI-compatible base URL (e.g. `https://host.your-tailnet.ts.net`) + bearer
  token. The `model` field is your gateway's agent/model name.
- **Speech to text**: on-device Vosk, or your self-hosted Whisper (URL + token), or OpenAI.
- **Text to speech**: on-device Sherpa, self-hosted Kokoro (URL + token), Venice.ai, or ElevenLabs.
- **Behavior**: speak-aloud, streaming, over-lock panel, pre-generate audio.
- **Power saver** (needs root): "Airplane when idle" cuts all radios after the screen's been off,
  and restores them when you wake the device or press PTT. Tap **Test root access** once to trigger
  the Magisk grant prompt. ⚠️ True airplane mode means **no calls/SMS while idle** — see CAVEATS.

All of these are stored **encrypted on-device**; nothing is hardcoded or committed.

## 9. Use it

Hold the side button, speak, release. Text streams in and (if enabled) is spoken. Hold again to
continue the conversation — after the first reply it opens a persistent **chat page**. There's also
a typed-message box and a mute toggle on that page.
