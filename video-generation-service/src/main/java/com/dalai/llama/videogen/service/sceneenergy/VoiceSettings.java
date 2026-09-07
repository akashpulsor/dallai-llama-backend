package com.dalai.llama.videogen.service.sceneenergy;

import java.math.BigDecimal;

/** ElevenLabs' own documented TTS levers -- {@code stability} (0-1, lower = more variable/
 * expressive), {@code style} (0-1, exaggeration of the voice's own style, higher = more
 * expressive but less stable). Both accepted on every ElevenLabs TTS call regardless of model. */
public record VoiceSettings(BigDecimal stability, BigDecimal style) {
}
