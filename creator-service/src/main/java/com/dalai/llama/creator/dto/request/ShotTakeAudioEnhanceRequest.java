package com.dalai.llama.creator.dto.request;

import java.util.Map;
import java.util.UUID;

public record ShotTakeAudioEnhanceRequest(
        String editNote,
        String provider,
        String model,
        UUID sourceAssetId,
        Double noiseReductionStrength,
        Double clarityBoost,
        Double deReverbStrength,
        Double loudnessTargetLufs,
        Boolean preserveVoiceTexture,
        Boolean preserveRoomTone,
        Map<String, Object> overrides
) {
}
