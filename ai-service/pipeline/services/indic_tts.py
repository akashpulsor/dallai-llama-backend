"""Local HTTP-backed Indic TTS service for test mode."""
from dataclasses import dataclass
from typing import AsyncGenerator, Optional

import aiohttp

from pipecat.frames.frames import ErrorFrame, Frame, TTSStartedFrame, TTSStoppedFrame
from pipecat.services.settings import TTSSettings
from pipecat.services.tts_service import TTSService
from pipecat.utils.tracing.service_decorators import traced_tts


@dataclass
class IndicTTSSettings(TTSSettings):
    """Settings for local Indic TTS."""

    emotion: Optional[str] = None


class IndicHttpTTSService(TTSService):
    """Calls a local Indic TTS HTTP endpoint that returns WAV/PCM audio."""

    _settings: IndicTTSSettings

    def __init__(
        self,
        *,
        base_url: str,
        aiohttp_session: aiohttp.ClientSession,
        voice: Optional[str] = None,
        language: Optional[str] = None,
        emotion: Optional[str] = None,
        sample_rate: int = 16000,
        **kwargs,
    ):
        super().__init__(
            sample_rate=sample_rate,
            settings=IndicTTSSettings(model=None, voice=voice, language=language, emotion=emotion),
            **kwargs,
        )
        self._base_url = base_url.rstrip("/")
        self._session = aiohttp_session

    def can_generate_metrics(self) -> bool:
        return True

    async def cleanup(self):
        if self._session and not self._session.closed:
            await self._session.close()
        await super().cleanup()

    @traced_tts
    async def run_tts(self, text: str, context_id: str) -> AsyncGenerator[Frame, None]:
        headers = {"Content-Type": "application/json"}
        payload = {
            "text": text,
            "voice": self._settings.voice,
            "language": self._settings.language,
            "emotion": self._settings.emotion,
            "sample_rate": self.sample_rate,
        }
        try:
            await self.start_ttfb_metrics()
            async with self._session.post(self._base_url, json=payload, headers=headers) as response:
                if response.status != 200:
                    error = await response.text()
                    yield ErrorFrame(
                        error=f"Indic TTS request failed (status: {response.status}, error: {error})"
                    )
                    return

                await self.start_tts_usage_metrics(text)
                yield TTSStartedFrame(context_id=context_id)

                async for frame in self._stream_audio_frames_from_iterator(
                    response.content.iter_chunked(self.chunk_size),
                    strip_wav_header=True,
                    context_id=context_id,
                ):
                    await self.stop_ttfb_metrics()
                    yield frame
        except Exception as exc:
            yield ErrorFrame(error=f"Indic TTS error: {exc}")
        finally:
            await self.stop_ttfb_metrics()
            yield TTSStoppedFrame(context_id=context_id)
