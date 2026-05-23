package com.dalai.llama.creator.dto.response;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedScriptResponse {
    private UUID scriptId;
    private UUID ideaId;
    private UUID lockedIdeaId;
    private UUID projectId;
    private UUID promptRunId;
    private String title;
    private String script;
    private CinematicScript scriptJson;
    private Map<String, Object> rawPromptResponse;
    private List<CinematicShot> scenes;
    private List<ShotProductionPlanTagResponse> productionPlanTags;
    private String productionPlanStatus;
    private String productionPlanError;
    private Map<String, Object> productionPlanDebug;
    private Integer durationSeconds;
    private String status;
    private OffsetDateTime generatedAt;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class CinematicScript {
        private String projectTitle;
        private Integer duration;
        private String formatTier;
        private String actStructure;
        private String budgetTier;
        private Integer totalShots;
        private Integer sceneCount;
        private Integer sequenceCount;
        private String pacingStyle;
        private String emotionalArc;
        private String hookStrategy;
        private String loopBridgeNotes;
        private String closingImageNotes;
        private Map<String, Object> characterVoiceProfiles;
        private Map<String, Object> continuityBible;
        private List<Object> dialogueCallbacks;
        private List<String> toneAnchors;
        private Map<String, Object> soundDesignPlan;
        private Map<String, Object> backgroundMusicPlan;
        private List<Map<String, Object>> shootingSchedule;
        private String creatorFitReasoning;
        private String audienceFitReasoning;
        private String overallExecutionDifficulty;
        private String category;
        private String inferredTone;
        private String dialogueLanguage;
        private String screenType;
        @JsonProperty("_validationContract")
        private Map<String, Object> validationContract;
        private String provider;
        private String model;
        private List<CinematicShot> shots;
        private List<Map<String, Object>> scenes;
        private List<Map<String, Object>> sequences;
        @Builder.Default
        private Map<String, Object> extra = new LinkedHashMap<>();

        @JsonAnySetter
        public void putExtra(String key, Object value) {
            if (extra == null) {
                extra = new LinkedHashMap<>();
            }
            extra.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getExtra() {
            if (extra == null) {
                extra = new LinkedHashMap<>();
            }
            return extra;
        }
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class CinematicShot {
        private Integer shotNumber;
        private Integer beatNumber;
        private String beatTitle;
        private Object startTime;
        private Object endTime;
        private Double durationSeconds;
        private String title;
        private String purpose;
        private String narrativeBeat;
        private String shotType;
        private String cameraAngle;
        private String cameraMovement;
        private String lensSuggestion;
        private Integer fps;
        private String coverageType;
        private String screenDirection;
        private String composition;
        private String setDesign;
        private String blockingNotes;
        private Integer peopleInFrame;
        private List<String> primaryCharacters;
        private List<String> sideCharacters;
        private List<String> primaryActors;
        private List<String> sideActors;
        private String primaryCharacterAction;
        private String primaryActorAction;
        private String sideActorAction;
        private String expression;
        private String emotion;
        private Double emotionIntensity;
        private String bodyLanguage;
        private String lighting;
        private String lightingMobile;
        private String lightingProfessional;
        private String lightingMobileFallback;
        private String environment;
        private String action;
        private String voiceOver;
        private Map<String, Object> dialogue;
        private String dialogueCraftNotes;
        private String textOverlay;
        private String transition;
        private List<Object> soundDesign;
        private String ambientBedDescription;
        private String syncHitDescription;
        private Map<String, Object> backgroundMusicCue;
        private List<Object> microNoveltyTriggers;
        private List<Object> editingNotes;
        private String retentionGoal;
        private String creatorDirection;
        private String directorNotes;
        private String subtitlePosition;
        private String mobileFocusArea;
        private String safeZoneNotes;
        private Map<String, Object> multiAspectFraming;
        private String continuityNotes;
        private List<String> culturalReferences;
        private Object executionDifficulty;
        private Object cinematicExecution;
        private Object rookieFriendlyGuide;
        private Map<String, Object> resourceRequirements;
        private Integer shootDay;
        private String shootBlock;
        private List<String> safetyFlags;
        private Boolean requiresCoordinator;
        private String complianceNotes;
        private Map<String, Object> postProductionNotes;
        private List<Map<String, Object>> captionTrack;
        private String audioDescription;
        private String sketchPrompt;
        private String audienceReason;
        private String castReason;
        @Builder.Default
        private Map<String, Object> extra = new LinkedHashMap<>();

        @JsonAnySetter
        public void putExtra(String key, Object value) {
            if (extra == null) {
                extra = new LinkedHashMap<>();
            }
            extra.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getExtra() {
            if (extra == null) {
                extra = new LinkedHashMap<>();
            }
            return extra;
        }
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExecutionDifficulty {
        private Integer score;
        private String level;
        private Boolean requiresTripod;
        private Boolean requiresHelper;
        private Boolean phoneFriendly;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CinematicExecution {
        private Integer recommendedFPS;
        private String captureMode;
        private String playbackSpeed;
        private String cameraStyle;
        private String stabilization;
        private String transitionStyle;
        private String zoomRecommendation;
        private String motionIntensity;
        private String editingComplexity;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RookieFriendlyGuide {
        private String whatIsThis;
        private String whyThisWorks;
        private List<String> howToShoot;
        private List<String> howToMoveCamera;
        private List<String> howToAct;
        private String editingTip;
        private List<String> commonMistakes;
        private Boolean phoneOnlyFriendly;
    }
}
