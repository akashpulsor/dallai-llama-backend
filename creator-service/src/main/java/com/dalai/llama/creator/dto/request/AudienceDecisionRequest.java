package com.dalai.llama.creator.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudienceDecisionRequest {

    @Size(max = 96)
    private String id;

    @Size(max = 96)
    private String projectId;

    @Size(max = 96)
    private String lockedIdeaId;

    @Size(max = 96)
    private String storyIdeaId;

    @Size(max = 96)
    private String scriptId;

    @Size(max = 96)
    private String trendId;

    @Size(max = 80)
    private String categoryCode;

    @Size(max = 8)
    private String countryCode;

    @Size(max = 220)
    private String title;

    @Size(max = 1200)
    private String description;

    @Size(max = 80)
    private String gender;

    @Size(max = 80)
    private String ageGroup;

    @Size(max = 120)
    private String location;

    @Size(max = 240)
    private String contentPreference;

    @Size(max = 4000)
    private String idea;

    private List<String> interests;
    private Map<String, Object> demographics;
    private Map<String, Object> psychographics;
    private Map<String, Object> brandContext;
    private Map<String, Object> trend;
    private Map<String, Object> selectedIdea;
    private Map<String, Object> storyScriptJson;
    private Map<String, Object> scriptJson;
    private Map<String, Object> castPlan;
    private Map<String, Object> context;
    private List<Map<String, Object>> characterCastMappings;
}
