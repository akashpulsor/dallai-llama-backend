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
public class CastProfileResponse {
    private UUID id;
    private UUID projectId;
    private String name;
    private String displayName;
    private String roleInShort;
    private Integer age;
    private String gender;
    private List<String> vibe;
    private List<String> vibes;
    private String style;
    private String cameraConfidence;
    private String look;
    private String profile;
    private String notes;
    private Boolean confirmed;
    private Map<String, Object> attributes;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
