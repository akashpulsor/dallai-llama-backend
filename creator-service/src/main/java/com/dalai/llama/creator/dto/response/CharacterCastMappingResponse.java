package com.dalai.llama.creator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CharacterCastMappingResponse {
    private UUID lockedIdeaId;
    private UUID storyIdeaId;
    private UUID projectId;
    private UUID scriptId;
    private List<Item> mappings;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private UUID id;
        private String characterKey;
        private String characterName;
        private String characterRole;
        private UUID castProfileId;
        private String castDisplayName;
        private Map<String, Object> characterPayload;
        private Map<String, Object> castPayload;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
    }
}
