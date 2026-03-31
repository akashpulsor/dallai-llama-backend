"""HTTP-backed Kokoro TTS service."""
from __future__ import annotations

from dataclasses import dataclass
from typing import AsyncGenerator

import httpx

from pipecat.frames.frames import ErrorFrame, Frame, TTSStartedFrame, TTSStoppedFrame
from pipecat.services.settings import TTSSettings
from pipecat.services.tts_service import TTSService
from pipecat.utils.tracing.service_decorators import traced_tts


@dataclass
class KokoroTTSSettings(TTSSettings):
    speed: float = 1.0


class KokoroHttpTTSService(TTSService):
    """Calls a Kokoro HTTP endpoint that returns WAV audio."""

    _settings: KokoroTTSSettings

    def __init__(
        self,
        *,
        base_url: str,
        voice: str,
        language: str = "hi",
        speed: float = 1.0,
        timeout_seconds: float = 45.0,
        sample_rate: int = 24000,
        **kwargs,
    ):
        super().__init__(
            sample_rate=sample_rate,
            settings=KokoroTTSSettings(model=None, voice=voice, language=language, speed=speed),
            **kwargs,
        )
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds

    def can_generate_metrics(self) -> bool:
        return True

    @traced_tts
    async def run_tts(self, text: str, context_id: str) -> AsyncGenerator[Frame, None]:
        try:
            await self.start_ttfb_metrics()
            async with httpx.AsyncClient(timeout=self._timeout_seconds) as client:
                response = await client.post(
                    self._base_url,
                    json={
                        "text": text,
                        "voice": self._settings.voice,
                        "language": self._settings.language,
                        "speed": self._settings.speed,
                        "sample_rate": self.sample_rate,
                    },
                    headers={"Content-Type": "application/json"},
                )
                response.raise_for_status()

                await self.start_tts_usage_metrics(text)
                yield TTSStartedFrame(context_id=context_id)

                async def chunk_iterator():
                    payload = response.content
                    for index in range(0, len(payload), self.chunk_size):
                        yield payload[index : index + self.chunk_size]

                async for frame in self._stream_audio_frames_from_iterator(
                    chunk_iterator(),
                    strip_wav_header=True,
                    context_id=context_id,
                ):
                    await self.stop_ttfb_metrics()
                    yield frame
        except Exception as exc:
            yield ErrorFrame(error=f"Kokoro TTS error: {exc}")
        finally:
            await self.stop_ttfb_metrics()
            yield TTSStoppedFrame(context_id=context_id)
