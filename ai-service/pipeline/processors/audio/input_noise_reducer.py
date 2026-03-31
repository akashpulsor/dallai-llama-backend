"""
Lightweight input audio cleanup before STT.

This is intentionally simple and CPU-friendly:
  1. DC offset removal
  2. One-pole high-pass filter to cut hum / low-frequency rumble
  3. Adaptive noise gate with soft attenuation

It operates on transport input audio frames and preserves Pipecat metadata.
"""
import logging
from dataclasses import replace
from math import pi

import numpy as np
from pipecat.frames.frames import Frame, InputAudioRawFrame
from pipecat.processors.frame_processor import FrameDirection, FrameProcessor

logger = logging.getLogger(__name__)


class InputNoiseReducer(FrameProcessor):
    """Reduce steady background noise on inbound PCM before STT."""

    def __init__(
        self,
        sample_rate: int = 16000,
        highpass_hz: float = 80.0,
        lowpass_hz: float = 3400.0,
        gate_ratio: float = 2.5,
        attenuation: float = 0.08,
        enabled: bool = True,
        **kwargs,
    ):
        super().__init__(**kwargs)
        self.sample_rate = sample_rate
        self.highpass_hz = highpass_hz
        self.lowpass_hz = lowpass_hz
        self.gate_ratio = gate_ratio
        self.attenuation = attenuation
        self.enabled = enabled
        self._prev_x = 0.0
        self._prev_y = 0.0
        self._lp_prev = 0.0
        self._noise_floor = 250.0

        rc = 1.0 / (2.0 * pi * max(self.highpass_hz, 1.0))
        dt = 1.0 / float(self.sample_rate)
        self._hp_alpha = rc / (rc + dt)
        lp_rc = 1.0 / (2.0 * pi * max(self.lowpass_hz, 1.0))
        self._lp_alpha = dt / (lp_rc + dt)

    async def process_frame(self, frame: Frame, direction: FrameDirection):
        await super().process_frame(frame, direction)

        if not self.enabled or not isinstance(frame, InputAudioRawFrame):
            await self.push_frame(frame, direction)
            return

        try:
            processed = self._process(frame.audio)
            await self.push_frame(replace(frame, audio=processed), direction)
        except Exception as exc:
            logger.warning("Input noise reduction failed, passing through original audio: %s", exc)
            await self.push_frame(frame, direction)

    def _process(self, pcm_data: bytes) -> bytes:
        if len(pcm_data) < 4:
            return pcm_data

        samples = np.frombuffer(pcm_data, dtype=np.int16).astype(np.float32)

        # Remove DC offset.
        samples -= np.mean(samples)

        # Simple telephony-style band-pass: high-pass for rumble, low-pass for hiss.
        alpha = self._hp_alpha
        prev_x = self._prev_x
        prev_y = self._prev_y
        filtered = np.empty_like(samples)
        for i, x in enumerate(samples):
            y = alpha * (prev_y + x - prev_x)
            filtered[i] = y
            prev_x = x
            prev_y = y
        self._prev_x = prev_x
        self._prev_y = prev_y

        lp_alpha = self._lp_alpha
        lp_prev = self._lp_prev
        for i, x in enumerate(filtered):
            lp_prev = lp_prev + lp_alpha * (x - lp_prev)
            filtered[i] = lp_prev
        self._lp_prev = lp_prev

        rms = float(np.sqrt(np.mean(filtered ** 2)))

        # Track a conservative noise floor so short speech bursts do not raise it aggressively.
        if rms < self._noise_floor * 1.2:
            self._noise_floor = 0.95 * self._noise_floor + 0.05 * max(rms, 50.0)
        else:
            self._noise_floor = 0.995 * self._noise_floor + 0.005 * self._noise_floor

        threshold = max(self._noise_floor * self.gate_ratio, 300.0)
        if rms < threshold * 0.65:
            return b"\x00" * len(pcm_data)
        if rms < threshold:
            filtered *= self.attenuation

        filtered = np.clip(filtered, -32768, 32767)
        return filtered.astype(np.int16).tobytes()
