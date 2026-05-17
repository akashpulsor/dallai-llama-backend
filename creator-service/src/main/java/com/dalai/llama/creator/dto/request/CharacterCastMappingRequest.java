package com.dalai.llama.creator.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CharacterCastMappingRequest(
        UUID projectId,
        UUID scriptId,
        @NotEmpty List<@Valid CharacterCastMappingItem> mappings
) {
    public record CharacterCastMappingItem(
            @NotBlank @Size(max = 160) String characterKey,
            @NotBlank @Size(max = 160) String characterName,
            @Size(max = 120) String characterRole,
            UUID castProfileId,
            @Size(max = 160) String castDisplayName,
            Map<String, Object> characterPayload,
            Map<String, Object> castPayload
    ) {
    }
}
