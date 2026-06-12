package com.dalai.llama.creator.dto.request;

import java.util.Map;

public record ShotTakeSoundGenerateRequest(
        String prompt,
        String layerType,
        Double startSeconds,
        Double endSeconds,
        Double durationSeconds,
        Double volumeDb,
        String provider,
        String model,
        String mood,
        String instrumentation,
        String bpmRange,
        Boolean loopable,
        Boolean avoidVocals,
        Map<String, Object> mixSettings,
        Map<String, Object> metadata
) {
}
