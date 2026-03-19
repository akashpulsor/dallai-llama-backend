"""
RVC voice conversion processor.

Transforms TTS audio through RVC model for consistent bot voice.
Sends PCM to external RVC server: POST /convert?model_id=X&sample_rate=24000
RVC server returns transformed PCM (same length, different voice).

When disabled or server unreachable, passes audio through unchanged.
Adds ~30-50ms on GPU, ~200ms on CPU.
"""
import logging
from dataclasses import replace
import httpx
from pipecat.frames.frames import Frame, OutputAudioRawFrame
from pipecat.processors.frame_processor import FrameDirection, FrameProcessor

logger = logging.getLogger(__name__)


class RvcProcessor(FrameProcessor):

    def __init__(self, server_url: str, model_id: str = "default",
                 sample_rate: int = 24000, enabled: bool = True, **kwargs):
        super().__init__(**kwargs)
        self.server_url = server_url.rstrip("/")
        self.model_id = model_id
        self.sample_rate = sample_rate
        self.enabled = enabled
        self._client: httpx.AsyncClient | None = None

    def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                timeout=httpx.Timeout(5.0, connect=2.0),
                limits=httpx.Limits(max_connections=5),
            )
        return self._client

    async def process_frame(self, frame: Frame, direction: FrameDirection):
        await super().process_frame(frame, direction)

        if not self.enabled or not isinstance(frame, OutputAudioRawFrame):
            await self.push_frame(frame, direction)
            return

        try:
            resp = await self._get_client().post(
                f"{self.server_url}/convert",
                content=frame.audio,
                params={"model_id": self.model_id, "sample_rate": self.sample_rate},
                headers={"Content-Type": "application/octet-stream"},
            )
            if resp.status_code == 200:
                await self.push_frame(replace(frame, audio=resp.content), direction)
                return
        except httpx.ConnectError:
            logger.warning("RVC server unreachable, disabling for this call")
            self.enabled = False
        except Exception as e:
            logger.warning("RVC error: %s", e)

        # Fallback: pass original
        await self.push_frame(frame, direction)

    async def cleanup(self):
        if self._client and not self._client.is_closed:
            await self._client.aclose()
