"""
ClawPTT STT service — a minimal OpenAI-compatible faster-whisper wrapper.

Exposes:  POST /v1/audio/transcriptions   (multipart: file=<wav>, model=<label>)
Auth:     Authorization: Bearer $STT_TOKEN
Returns:  {"text": "..."}

Env:
  STT_TOKEN        (required) dedicated bearer token for this service
  WHISPER_MODEL    tiny | base | small | medium | large-v3   (default: base)
  WHISPER_DEVICE   cpu | cuda                                 (default: cpu)
  WHISPER_COMPUTE  int8 (cpu) | float16 (gpu)                 (default: int8)
  STT_MAX_MB       max upload size in MB                      (default: 25)
"""
import os
import tempfile
from fastapi import FastAPI, UploadFile, Form, Header, HTTPException
from faster_whisper import WhisperModel

STT_TOKEN   = os.environ["STT_TOKEN"]
MODEL_SIZE  = os.environ.get("WHISPER_MODEL", "base")
DEVICE      = os.environ.get("WHISPER_DEVICE", "cpu")
COMPUTE     = os.environ.get("WHISPER_COMPUTE", "int8")
MAX_MB      = int(os.environ.get("STT_MAX_MB", "25"))

app = FastAPI(title="ClawPTT STT")
WHISPER = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type=COMPUTE)


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_SIZE, "device": DEVICE}


@app.post("/v1/audio/transcriptions")
async def transcriptions(
    file: UploadFile,
    model: str = Form("whisper-1"),          # OpenAI compat label; ignored
    language: str | None = Form(None),
    authorization: str | None = Header(None),
):
    if authorization != f"Bearer {STT_TOKEN}":
        raise HTTPException(401, "unauthorized")
    data = await file.read()
    if len(data) > MAX_MB * 1024 * 1024:
        raise HTTPException(413, f"file too large (> {MAX_MB} MB)")
    with tempfile.NamedTemporaryFile(suffix=".wav") as tf:
        tf.write(data)
        tf.flush()
        segments, _ = WHISPER.transcribe(tf.name, language=language, beam_size=1)
        text = "".join(s.text for s in segments).strip()
    return {"text": text}
