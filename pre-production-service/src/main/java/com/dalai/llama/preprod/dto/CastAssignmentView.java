package com.dalai.llama.preprod.dto;

import java.util.UUID;

public record CastAssignmentView(
        UUID id,
        UUID scriptCharacterId,
        UUID castProfileId,
        String wardrobeNote,
        String performanceDirection
) {
}
