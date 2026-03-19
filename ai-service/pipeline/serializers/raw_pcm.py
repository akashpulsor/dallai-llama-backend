from typing import Optional

from pydantic import BaseModel

from pipecat.frames.frames import Frame, InputAudioRawFrame, OutputAudioRawFrame
from pipecat.serializers.base_serializer import FrameSerializer


class RawPCMSerializer(FrameSerializer):
    """Pass raw PCM16 mono audio frames over WebSocket as bare binary payloads."""

    class InputParams(BaseModel):
        audio_in_sample_rate: int = 16000
        audio_out_sample_rate: int = 24000
        num_channels: int = 1

    def __init__(self, params: Optional[InputParams] = None):
        super().__init__(params or RawPCMSerializer.InputParams())
        self._audio_in_sample_rate = self._params.audio_in_sample_rate
        self._audio_out_sample_rate = self._params.audio_out_sample_rate
        self._num_channels = self._params.num_channels

    async def serialize(self, frame: Frame) -> str | bytes | None:
        if isinstance(frame, OutputAudioRawFrame):
            return frame.audio
        return None

    async def deserialize(self, data: str | bytes) -> Frame | None:
        if isinstance(data, bytes) and data:
            return InputAudioRawFrame(
                audio=data,
                sample_rate=self._audio_in_sample_rate,
                num_channels=self._num_channels,
            )
        return None
