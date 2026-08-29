"""
ClawPTT Kokoro TTS service — OpenAI-compatible, streaming.

Exposes:  POST /v1/audio/speech
Auth:     Authorization: Bearer $TTS_TOKEN
Body:     {"model":"kokoro","input":"...","voice":"af_bella","response_format":"pcm","stream":true}
Returns:  streamed 24 kHz mono s16le PCM (response_format=pcm)  — or a WAV blob (response_format=wav)

The PCM is streamed per Kokoro segment, so ClawPTT starts playing on the first chunk (gapless).

Env:
  TTS_TOKEN       (required) dedicated bearer token
  KOKORO_LANG     Kokoro lang code (default 'a' = American English; 'b' = British)
  KOKORO_VOICE    default voice (default 'af_bella')
"""
import os
import struct
import numpy as np
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel
from kokoro import KPipeline

TTS_TOKEN     = os.environ["TTS_TOKEN"]
LANG          = os.environ.get("KOKORO_LANG", "a")
DEFAULT_VOICE = os.environ.get("KOKORO_VOICE", "af_bella")
SR            = 24000

app = FastAPI(title="ClawPTT Kokoro TTS")
pipe = KPipeline(lang_code=LANG)


class SpeechReq(BaseModel):
    model: str = "kokoro"
    input: str
    voice: str | None = None
    response_format: str = "pcm"
    stream: bool = True


def _to_pcm16(audio) -> bytes:
    a = audio.detach().cpu().numpy() if hasattr(audio, "detach") else np.asarray(audio, dtype="float32")
    a = np.clip(a, -1.0, 1.0)
    return (a * 32767.0).astype("<i2").tobytes()


def _wav_header(datalen: int, sr: int) -> bytes:
    return (b"RIFF" + struct.pack("<I", 36 + datalen) + b"WAVE" + b"fmt " +
            struct.pack("<IHHIIHH", 16, 1, 1, sr, sr * 2, 2, 16) + b"data" + struct.pack("<I", datalen))


@app.get("/health")
def health():
    return {"status": "ok", "voice": DEFAULT_VOICE, "sample_rate": SR}


@app.post("/v1/audio/speech")
def speech(req: SpeechReq, authorization: str | None = Header(None)):
    if authorization != f"Bearer {TTS_TOKEN}":
        raise HTTPException(401, "unauthorized")
    voice = req.voice or DEFAULT_VOICE

    def gen():
        for _, _, audio in pipe(req.input, voice=voice):
            yield _to_pcm16(audio)

    if req.response_format == "wav":
        pcm = b"".join(gen())
        return Response(_wav_header(len(pcm), SR) + pcm, media_type="audio/wav")
    # default: streamed raw PCM (24 kHz mono s16le)
    return StreamingResponse(gen(), media_type="audio/pcm")
