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
public class GeneratedScriptResponse {
    private UUID scriptId;
    private UUID ideaId;
    private UUID lockedIdeaId;
    private UUID promptRunId;
    private String title;
    private String script;
    private CinematicScript scriptJson;
    private List<CinematicShot> scenes;
    private Integer durationSeconds;
    private String status;
    private OffsetDateTime generatedAt;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CinematicScript {
        private String projectTitle;
        private Integer duration;
        private Integer totalShots;
        private String pacingStyle;
        private String emotionalArc;
        private String hookStrategy;
        private String creatorFitReasoning;
        private String audienceFitReasoning;
        private String overallExecutionDifficulty;
        private String category;
        private String inferredTone;
        private String dialogueLanguage;
        private String screenType;
        private String provider;
        private String model;
        private List<CinematicShot> shots;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CinematicShot {
        private Integer shotNumber;
        private String startTime;
        private String endTime;
        private Integer durationSeconds;
        private String title;
        private String purpose;
        private String shotType;
        private String cameraAngle;
        private String cameraMovement;
        private String lensSuggestion;
        private Integer fps;
        private String composition;
        private String setDesign;
        private Integer peopleInFrame;
        private List<String> primaryActors;
        private List<String> sideActors;
        private String primaryActorAction;
        private String sideActorAction;
        private String expression;
        private String emotion;
        private String bodyLanguage;
        private String lighting;
        private String environment;
        private String action;
        private String voiceOver;
        private Map<String, String> dialogue;
        private String textOverlay;
        private String transition;
        private List<String> soundDesign;
        private List<String> editingNotes;
        private String retentionGoal;
        private String creatorDirection;
        private String subtitlePosition;
        private String mobileFocusArea;
        private String safeZoneNotes;
        private ExecutionDifficulty executionDifficulty;
        private CinematicExecution cinematicExecution;
        private RookieFriendlyGuide rookieFriendlyGuide;
        private String sketchPrompt;
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
