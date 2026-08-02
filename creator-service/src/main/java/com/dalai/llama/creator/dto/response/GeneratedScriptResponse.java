package com.dalai.llama.creator.dto.response;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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
        private Map<String, Object> videoPacingProfile;
        private Map<String, Object> seedancePromptStrategy;
        private Map<String, Object> videoConsistencyBible;
        private List<Map<String, Object>> srtCues;
        private Map<String, Object> srtFile;
        private String srt;
        private List<Map<String, Object>> shootingSchedule;
        private String creatorFitReasoning;
        private String audienceFitReasoning;
        private String overallExecutionDifficulty;
        private String category;
        private String inferredTone;
        private String dialogueLanguage;
        private String screenType;
        private String storytellingType;
        private Map<String, Object> storytellingGuidance;
        private String hookLens;
        private Map<String, Object> hookLensGuidance;
        private String productionStyle;
        private String hybridSceneMode;
        private String brollStyle;
        private String captionStyle;
        private Map<String, Object> productionStyleGuidance;
        private Map<String, Object> hookBridge;
        private Map<String, Object> factualityNotes;
        private Map<String, Object> shotMixPlan;
        @JsonProperty("_validationContract")
        private Map<String, Object> validationContract;
        private String provider;
        private String model;
        private List<CinematicShot> shots;
        private List<Map<String, Object>> scenes;
        private List<Map<String, Object>> sequences;
        @Builder.Default
        private Map<String, Object> extra = new LinkedHashMap<>();

        @JsonSetter("dialogueCallbacks")
        public void setDialogueCallbacks(Object value) {
            this.dialogueCallbacks = objectList(value);
        }

        @JsonSetter("toneAnchors")
        public void setToneAnchors(Object value) {
            this.toneAnchors = stringList(value);
        }

        @JsonSetter("shootingSchedule")
        public void setShootingSchedule(Object value) {
            this.shootingSchedule = mapList(value);
        }

        @JsonSetter("srtCues")
        public void setSrtCues(Object value) {
            this.srtCues = mapList(value);
        }

        @JsonSetter("scenes")
        public void setScenes(Object value) {
            this.scenes = mapList(value);
        }

        @JsonSetter("sequences")
        public void setSequences(Object value) {
            this.sequences = mapList(value);
        }

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
        private String storytellingRole;
        private String generationMode;
        private String targetProvider;
        private String assetCaptureMode;
        private String assetGenerationPrompt;
        private String brollStyle;
        private String captionStyle;
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
        private Map<String, Object> visualTreatment;
        private Object rookieFriendlyGuide;
        private Map<String, Object> resourceRequirements;
        private Integer shootDay;
        private String shootBlock;
        private List<String> safetyFlags;
        private Boolean requiresCoordinator;
        private String complianceNotes;
        private Map<String, Object> postProductionNotes;
        private List<Map<String, Object>> captionTrack;
        private Map<String, Object> videoContinuity;
        private String seedancePrompt;
        private String pacingPrompt;
        private List<Map<String, Object>> srtCues;
        private String audioDescription;
        private String sketchPrompt;
        private String audienceReason;
        private String castReason;
        @Builder.Default
        private Map<String, Object> extra = new LinkedHashMap<>();

        @JsonSetter("primaryCharacters")
        public void setPrimaryCharacters(Object value) {
            this.primaryCharacters = stringList(value);
        }

        @JsonSetter("sideCharacters")
        public void setSideCharacters(Object value) {
            this.sideCharacters = stringList(value);
        }

        @JsonSetter("primaryActors")
        public void setPrimaryActors(Object value) {
            this.primaryActors = stringList(value);
        }

        @JsonSetter("sideActors")
        public void setSideActors(Object value) {
            this.sideActors = stringList(value);
        }

        @JsonSetter("soundDesign")
        public void setSoundDesign(Object value) {
            this.soundDesign = objectList(value);
        }

        @JsonSetter("microNoveltyTriggers")
        public void setMicroNoveltyTriggers(Object value) {
            this.microNoveltyTriggers = objectList(value);
        }

        @JsonSetter("editingNotes")
        public void setEditingNotes(Object value) {
            this.editingNotes = objectList(value);
        }

        @JsonSetter("culturalReferences")
        public void setCulturalReferences(Object value) {
            this.culturalReferences = stringList(value);
        }

        @JsonSetter("safetyFlags")
        public void setSafetyFlags(Object value) {
            this.safetyFlags = stringList(value);
        }

        @JsonSetter("captionTrack")
        public void setCaptionTrack(Object value) {
            this.captionTrack = mapList(value);
        }

        @JsonSetter("srtCues")
        public void setShotSrtCues(Object value) {
            this.srtCues = mapList(value);
        }

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

    private static List<Object> objectList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        List<Object> items = new ArrayList<>(1);
        items.add(value);
        return items;
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    items.add(String.valueOf(item));
                }
            }
            return items;
        }
        items.add(String.valueOf(value));
        return items;
    }

    private static List<Map<String, Object>> mapList(Object value) {
        if (value == null) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    items.add(mapValue(item));
                }
            }
            return items;
        }
        items.add(mapValue(value));
        return items;
    }

    private static Map<String, Object> mapValue(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        } else {
            result.put("value", value);
        }
        return result;
    }
}
