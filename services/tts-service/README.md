# ClawPTT Kokoro TTS service

OpenAI-compatible `POST /v1/audio/speech` in front of Kokoro, **streaming** 24 kHz mono PCM so
ClawPTT plays gaplessly as audio is generated. Own bearer token, behind Tailscale. Mirrors the
STT service.

## Option A — this wrapper (self-contained)
```sh
python3 -m venv venv
./venv/bin/pip install -r requirements.txt
# Kokoro also needs espeak-ng on the system:  sudo apt-get install -y espeak-ng

export TTS_TOKEN="$(openssl rand -hex 24)"     # save this — goes in the app
export KOKORO_VOICE=af_bella                    # default voice
./venv/bin/uvicorn tts_service:app --host 127.0.0.1 --port 5002
```

## Option B — you already run kokoro-fastapi
`kokoro-fastapi` already serves an OpenAI-compatible `/v1/audio/speech`, but with **no auth**.
Put a tiny bearer-check reverse proxy (or Tailscale-side auth) in front of it and point the app
at that. Same request/response contract.

## Expose over Tailscale (separate HTTPS port, like the STT one)
```sh
tailscale serve --bg --https=9443 http://127.0.0.1:5002
# -> https://<host>.ts.net:9443/v1/audio/speech
```
App "Kokoro · Service URL" = `https://<host>.ts.net:9443`

## Test
```sh
curl -s -H "Authorization: Bearer $TTS_TOKEN" -H "Content-Type: application/json" \
  -d '{"model":"kokoro","input":"Hello from Kokoro.","voice":"af_bella","response_format":"wav"}' \
  https://<host>.ts.net:9443/v1/audio/speech -o out.wav && echo "wrote out.wav"
```

## Voices
Kokoro voice names like `af_bella`, `af_sky`, `af_nicole`, `am_adam`, `bf_emma` (British), etc.
Whatever you set as the app's **Voice** is sent as `voice`. The app requests `response_format=pcm`
(24 kHz mono s16le) and streams it into an AudioTrack.
