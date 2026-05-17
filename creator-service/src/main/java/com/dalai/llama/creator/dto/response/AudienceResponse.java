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
public class AudienceResponse {
    private UUID id;
    private UUID projectId;
    private String title;
    private String description;
    private String gender;
    private String ageGroup;
    private String location;
    private List<String> interests;
    private String contentPreference;
    private Boolean aiSuggested;
    private Boolean confirmed;
    private Map<String, Object> demographics;
    private Map<String, Object> psychographics;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
