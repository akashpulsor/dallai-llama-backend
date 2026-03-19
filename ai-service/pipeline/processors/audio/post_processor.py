"""
Audio post-processing — applied to TTS output before sending to FreeSWITCH.

Lightweight processing:
  1. Noise gate: silence very quiet audio (removes TTS artifacts)
  2. Normalization: consistent volume level
  3. DC offset removal

No heavy DSP — we need <5ms per chunk for real-time.
"""
import logging
from dataclasses import replace
import numpy as np
from pipecat.frames.frames import Frame, OutputAudioRawFrame
from pipecat.processors.frame_processor import FrameDirection, FrameProcessor

logger = logging.getLogger(__name__)


class AudioPostProcessor(FrameProcessor):
    """Cleans up TTS audio before sending to caller."""

    def __init__(self, noise_gate_threshold: int = 100,
                 target_rms: int = 3000, enabled: bool = True, **kwargs):
        super().__init__(**kwargs)
        self.noise_gate_threshold = noise_gate_threshold
        self.target_rms = target_rms
        self.enabled = enabled

    async def process_frame(self, frame: Frame, direction: FrameDirection):
        await super().process_frame(frame, direction)

        if not self.enabled or not isinstance(frame, OutputAudioRawFrame):
            await self.push_frame(frame, direction)
            return

        try:
            processed = self._process(frame.audio)
            await self.push_frame(replace(frame, audio=processed), direction)
        except Exception:
            await self.push_frame(frame, direction)

    def _process(self, pcm_data: bytes) -> bytes:
        if len(pcm_data) < 4:
            return pcm_data

        samples = np.frombuffer(pcm_data, dtype=np.int16).astype(np.float32)

        # DC offset removal
        samples -= np.mean(samples)

        # Noise gate: if RMS below threshold, silence it
        rms = np.sqrt(np.mean(samples ** 2))
        if rms < self.noise_gate_threshold:
            return b'\x00' * len(pcm_data)

        # Normalization: scale to target RMS
        if rms > 0:
            gain = self.target_rms / rms
            gain = min(gain, 5.0)  # Cap gain to prevent distortion
            samples *= gain

        # Clip to int16 range
        samples = np.clip(samples, -32768, 32767)
        return samples.astype(np.int16).tobytes()
