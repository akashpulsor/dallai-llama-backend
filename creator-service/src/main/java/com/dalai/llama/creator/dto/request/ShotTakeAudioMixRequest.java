package com.dalai.llama.creator.dto.request;

import java.util.List;
import java.util.Map;

public record ShotTakeAudioMixRequest(
        List<Map<String, Object>> layers,
        Map<String, Object> mixSettings,
        String renderMode,
        String provider,
        String model,
        Map<String, Object> metadata
) {
}
