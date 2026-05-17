package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CastProfileRequest(
        UUID projectId,
        @Size(max = 160) String name,
        @Size(max = 160) String displayName,
        @Size(max = 120) String roleInShort,
        Integer age,
        @Size(max = 64) String gender,
        List<String> vibe,
        List<String> vibes,
        @Size(max = 160) String style,
        @Size(max = 120) String cameraConfidence,
        String look,
        String profile,
        String notes,
        Boolean confirmed,
        Map<String, Object> attributes
) {
}
