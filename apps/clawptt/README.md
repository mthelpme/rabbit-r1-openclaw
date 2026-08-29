# ClawPTT

A hold-to-talk voice bridge for the Rabbit R1. Press and hold the side button, speak, release —
your words are transcribed, sent to an **OpenAI-compatible chat gateway**, and the reply is streamed
back and (optionally) spoken aloud. Follow-up presses open a persistent chat page with a typed-
message box and a mute toggle.

Kotlin, programmatic UI (no XML layouts), `minSdk 28`, AGP 8.5 / Gradle 8.7.

## Features
- Global hold/release PTT via an **AccessibilityService** (works on the lock screen).
- STT: on-device **Vosk** · self-hosted **Whisper** · OpenAI · Android SpeechRecognizer.
- TTS: on-device **SherpaTTS** · self-hosted **Kokoro** (streaming PCM) · **Venice.ai** · **ElevenLabs**.
- Streaming replies, sentence-chunked speech, conversation history, over-lock panel.
- Persistent **chat page** for continuous conversations; typed messages; mute toggle.
- **Power saver** (root): airplane-when-idle, restored on wake / PTT.
- All secrets stored with `EncryptedSharedPreferences` — entered on-device, never hardcoded.

## Build & install
```sh
export JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Then enable ClawPTT's **accessibility service** and (if using the on-device PTT button) flash the
`ptt-remap` Magisk module so the side key reports `KEYCODE_BUTTON_1`.

## Configure
Open the app → set your **Gateway** base URL + bearer token (any OpenAI-compatible
`/v1/chat/completions`), pick **STT**/**TTS**, and toggle behavior. Power saver → **Test root
access** to grant Magisk root.

## Design / brand rollback
The visual identity (colors, mascot, launcher icon) can be swapped between the official brand and
the original palette:
```sh
design/switch.sh legacy   # or: brand
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Notes
- Only the power saver requires root; the core voice loop does not.
- See the repo [SETUP](../../docs/SETUP.md) and [CAVEATS](../../docs/CAVEATS.md).
