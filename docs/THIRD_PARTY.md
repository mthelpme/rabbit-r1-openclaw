# Third-party notices & attributions

This project bundles or depends on the following. Their names/trademarks belong to their owners.

## Bundled in this repo
- **Figtree** (`apps/clawptt/app/src/main/res/font/figtree.ttf`) — SIL Open Font License 1.1.
- **Caprasimo** (`apps/clawptt/design/legacy/caprasimo.ttf`, kept only for the legacy design
  rollback) — SIL Open Font License 1.1.
- **Mascot artwork** (`apps/clawptt/.../mascot.png` and launcher icon) — original brand asset of
  this project, released under this repo's MIT license unless noted otherwise.

The OFL requires that the fonts' license accompany them; see the respective font projects
(Google Fonts: Figtree, Caprasimo) for the full OFL-1.1 text.

## Downloaded/used at runtime (not bundled)
- **Vosk** small English model — downloaded on first use by ClawPTT (Apache-2.0). alphacephei.com/vosk
- **SherpaTTS** — the on-device system TTS engine ClawPTT routes to.

## Server-side (self-hosted services)
- **faster-whisper** (STT service) — MIT. Model weights per OpenAI Whisper terms.
- **Kokoro** (TTS service) — see the Kokoro project's license.
- **FastAPI / uvicorn / starlette** — MIT/BSD.

## Platform / root ecosystem (not included; you install these yourself)
- **Magisk** (topjohnwu) — GPL-3.0.
- **Vector** (JingMatrix) — the maintained **LSPosed** successor; Xposed framework. GPL-3.0.
- **LineageOS 21** GSI — Apache-2.0 (+ component licenses).
- **microG** — Apache-2.0.
- **mtkclient** (bkerler) — GPL-3.0.
- **Tailscale** — BSD-3-Clause (client).

## APIs ClawPTT can talk to
- **OpenAI-compatible** `/v1/chat/completions` (your gateway / OpenClaw) — you provide it.
- **Venice.ai**, **ElevenLabs**, **OpenAI** — optional TTS/STT providers; used with your own keys
  under their respective terms.

## App dependencies (ClawPTT / R1 Tools / R1 Immersive)
- AndroidX (core-ktx, appcompat, security-crypto) — Apache-2.0.
- OkHttp (Square) — Apache-2.0.
- Vosk Android — Apache-2.0.
- Xposed API (`de.robv.android.xposed:api`, compile-only, R1 Immersive) — Apache-2.0.

If any attribution here is incomplete or incorrect, please open an issue.
