# ClawPTT STT service

A tiny OpenAI-compatible `POST /v1/audio/transcriptions` in front of the VPS's local
faster-whisper. Separate bearer token from the OpenClaw gateway. Runs on the VPS, exposed
to the R1 over Tailscale.

## Run
```sh
python3 -m venv venv
./venv/bin/pip install -r requirements.txt

export STT_TOKEN="$(openssl rand -hex 24)"      # save this — it goes in the app
export WHISPER_MODEL=base                        # tiny|base|small|medium|large-v3
# GPU box: export WHISPER_DEVICE=cuda WHISPER_COMPUTE=float16

./venv/bin/uvicorn stt_service:app --host 127.0.0.1 --port 5001
```

## Expose over Tailscale (pick one)

**A) Separate HTTPS port (simplest, no path rewriting):**
```sh
tailscale serve --bg --https=8443 http://127.0.0.1:5001
# -> https://<host>.ts.net:8443/v1/audio/transcriptions
```
App "Self-hosted Whisper service URL" = `https://<host>.ts.net:8443`

**B) Path under the existing root (if your Tailscale strips the prefix):**
```sh
tailscale serve --bg --set-path=/stt http://127.0.0.1:5001
# -> https://<host>.ts.net/stt/v1/audio/transcriptions
```
App URL = `https://<host>.ts.net/stt`  (verify with the curl test below)

## Test
```sh
curl -s -H "Authorization: Bearer $STT_TOKEN" \
  -F model=whisper-1 -F file=@sample.wav \
  https://<host>.ts.net:8443/v1/audio/transcriptions
# -> {"text":"..."}
```

## Model sizes vs the R1
`tiny`/`base` are fast and usually enough for commands; `small`/`medium` are more accurate
if the VPS has the CPU/GPU. Because this runs on the VPS (not the R1's chip), even `small`
will beat on-device whisperIME on latency.
