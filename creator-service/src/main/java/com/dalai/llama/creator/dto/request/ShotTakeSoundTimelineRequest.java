package com.dalai.llama.creator.dto.request;

import java.util.List;
import java.util.Map;

public record ShotTakeSoundTimelineRequest(
        List<Map<String, Object>> layers,
        Map<String, Object> mixSettings
) {
}
