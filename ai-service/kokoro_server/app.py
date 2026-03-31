"""Standalone Kokoro TTS HTTP server for local testing and Kubernetes deployment."""
from __future__ import annotations

import io
import logging
import os
from functools import lru_cache

import soundfile as sf
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)
logging.basicConfig(level=os.getenv("KOKORO_LOG_LEVEL", "INFO"))


LANGUAGE_CODES = {
    "hi": "h",
    "hi-in": "h",
    "en": "a",
    "en-us": "a",
    "en-gb": "b",
    "es": "e",
    "fr": "f",
    "it": "i",
    "pt-br": "p",
    "ja": "j",
    "zh": "z",
}


class SynthesizeRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=5000)
    voice: str = Field(default="hf_alpha", min_length=1, max_length=64)
    language: str = Field(default="hi", min_length=1, max_length=16)
    speed: float = Field(default=1.0, ge=0.5, le=2.0)
    sample_rate: int = Field(default=24000, ge=8000, le=48000)


app = FastAPI(title="Kokoro TTS Server", version="0.1.0")


@lru_cache(maxsize=8)
def get_pipeline(language: str):
    try:
        from kokoro import KPipeline
    except ModuleNotFoundError as exc:
        raise RuntimeError(
            "Kokoro server dependencies are missing. Install from kokoro_server/requirements.txt."
        ) from exc

    lang_key = LANGUAGE_CODES.get(language.lower())
    if not lang_key:
        raise ValueError(f"Unsupported Kokoro language: {language}")

    logger.info("Loading Kokoro pipeline for language=%s lang_code=%s", language, lang_key)
    return KPipeline(lang_code=lang_key)


def synthesize_audio(payload: SynthesizeRequest) -> bytes:
    pipeline = get_pipeline(payload.language)
    chunks = []
    try:
        generator = pipeline(payload.text, voice=payload.voice, speed=payload.speed)
        for _, _, audio in generator:
            chunks.append(audio)
    except Exception as exc:
        raise RuntimeError(f"Kokoro synthesis failed: {exc}") from exc

    if not chunks:
        raise RuntimeError("Kokoro returned no audio")

    import numpy as np

    combined = np.concatenate(chunks)
    buffer = io.BytesIO()
    sf.write(buffer, combined, payload.sample_rate, format="WAV")
    return buffer.getvalue()


@app.get("/health")
async def health():
    return {"status": "healthy"}


@app.post("/synthesize")
async def synthesize(payload: SynthesizeRequest):
    try:
        wav_bytes = synthesize_audio(payload)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("Kokoro synthesis failed")
        raise HTTPException(status_code=500, detail=str(exc)) from exc

    from fastapi.responses import Response

    return Response(content=wav_bytes, media_type="audio/wav")
