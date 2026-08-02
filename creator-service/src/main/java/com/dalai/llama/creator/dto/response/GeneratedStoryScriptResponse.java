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
public class GeneratedStoryScriptResponse {
    private UUID ideaId;
    private UUID lockedIdeaId;
    private UUID projectId;
    private UUID promptRunId;
    private String title;
    private String scriptText;
    private StoryScript scriptJson;
    private Map<String, Object> rawPromptResponse;
    private Integer durationSeconds;
    private String status;
    private OffsetDateTime generatedAt;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StoryScript {
        private String projectTitle;
        private Integer duration;
        private String category;
        private String dialogueLanguage;
        private String screenType;
        private String storytellingType;
        private Map<String, Object> storytellingGuidance;
        private Boolean noHumans;
        private String narrativeMode;
        private String dialogueMode;
        private String hookLens;
        private Map<String, Object> hookLensGuidance;
        private Map<String, Object> hookBridge;
        private Map<String, Object> factualityNotes;
        private String logline;
        private String centralConflict;
        private String storyline;
        private String emotionalArc;
        private String hook;
        private String endingPayoff;
        private String setting;
        private String inferredTone;
        private List<CharacterProfile> characters;
        private List<StoryBeat> beats;
        private Map<String, Object> llmGeneratedScript;
        private Map<String, Object> userRevision;
        private Map<String, Object> revisionAudit;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CharacterProfile {
        private String name;
        private String role;
        private String gender;
        private String age;
        private String ageRange;
        private String look;
        private String profile;
        private String persona;
        private String backstory;
        private String motivation;
        private String fearOrBlock;
        private String relationshipToStory;
        private String speakingStyle;
        private String visualIdentity;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StoryBeat {
        private Integer beatNumber;
        private String title;
        private String summary;
        private String characterFocus;
        private String emotionalPurpose;
        private Integer estimatedSeconds;
    }
}
