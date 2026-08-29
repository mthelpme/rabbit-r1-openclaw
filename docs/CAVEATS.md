# Caveats, risks & security

Please read before flashing anything.

## This can brick your device
Unlocking the bootloader and flashing custom firmware on a MediaTek device is inherently risky.
A bad flash can leave the R1 unbootable. You are responsible for your own device; there is **no
warranty** (see [`../LICENSE`](../LICENSE)). Have `mtkclient` and a known-good `vbmeta`/partition
backup ready before you start.

## Root & third-party frameworks
- Most of the "device polish" (camera motor, status-bar hide in apps, power saver, key remap)
  requires **Magisk root** and/or the **Vector (LSPosed)** Xposed framework — a third-party root
  component that hooks the Android framework. Installing an Xposed framework is powerful and can
  cause boot loops; Magisk's safe-mode / `--remove-modules` is your recovery path.
- Each rooted feature is **optional**. ClawPTT's core voice loop works without root; only the power
  saver needs it.

## Unofficial / trademarks
This is a community project. It is **not affiliated with, endorsed by, or supported by Rabbit Inc.**
"Rabbit" and "rabbit r1" are trademarks of their respective owner and are used here only to
describe the target hardware.

## Airplane power saver
The optional "Airplane when idle" feature uses **true airplane mode** — it turns off **all** radios.
While it's active you will **not receive calls, SMS, or push notifications**; incoming calls go to
voicemail and messages arrive late (when connectivity returns). It restores connectivity when you
wake the device or press PTT. If you rely on this device for calls, don't enable it (or adapt it to
toggle mobile *data* only).

## Accessibility service
ClawPTT uses an **AccessibilityService** solely to capture the hardware side button (`BUTTON_1`)
globally, including on the lock screen. It does not read screen content for any other purpose. The
source is here — audit it.

## Security / secrets
- **No secrets are in this repo.** Gateway URLs, API keys, and service tokens are entered on-device
  and stored with `EncryptedSharedPreferences`; the self-hosted services read their tokens from
  environment variables. **Never commit your own tokens, tailnet hostnames, or keys.**
- Expose your gateway and STT/TTS services over **Tailscale** (or another private network), not
  public ports. Use a separate bearer token per service.
- The app talks to whatever endpoint you configure. Point it only at servers you trust.

## Stability notes
- `policy_control` immersive (the `hide-statusbar` Magisk module) only reliably hides the status bar
  on the launcher under Android 14 — use **R1 Immersive** (LSPosed) for all-apps hiding.
- Vector/LSPosed module scope must include **all apps** for R1 Immersive to work everywhere; newly
  installed apps need a re-scope.
- Exact-alarm timing for the idle power-saver can slip slightly under Doze; it's best-effort.
