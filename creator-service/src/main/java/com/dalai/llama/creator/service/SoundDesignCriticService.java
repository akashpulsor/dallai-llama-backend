package com.dalai.llama.creator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Scores a SoundDesignPlanningService-generated music/ambience/SFX plan against the shot
 * sequence's emotional arc, and against the mix-standards constraint (dialogue must stay
 * intelligible) that ScreenplayVideoProviderGenerationService already hardcodes into its video
 * prompt (see ScreenplayVideoService.audioMixStandards() defaults: dialogue_first_music_ducked).
 * Text-only critic, same generateMetered + JSON-contract pattern as EditingPlanCriticService.
 */
@Service
public class SoundDesignCriticService {

    private static final Logger log = LoggerFactory.getLogger(SoundDesignCriticService.class);
    private static final String PROMPT_TYPE = "SOUND_DESIGN_CRITIC";

    private final CreatorAiService creatorAiService;
    private final ObjectMapper objectMapper;

    public SoundDesignCriticService(CreatorAiService creatorAiService, ObjectMapper objectMapper) {
        this.creatorAiService = creatorAiService;
        this.objectMapper = objectMapper;
    }

    public record SoundDesignCriticResult(
            String status,
            double confidence,
            int musicEmotionalArcFitScore,
            int dialogueAudibilityScore,
            int sfxRestraintScore,
            List<String> issues,
            String summary,
            double averageScore
    ) {
        public boolean isFail() {
            return "FAIL".equals(status);
        }
    }

    public SoundDesignCriticResult critique(
            Map<String, Object> soundDesignPlan,
            List<Map<String, Object>> shotSequenceSummary,
            String tenantId,
            String userId,
            UUID projectId
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("renderedPrompt", renderedPrompt(soundDesignPlan, shotSequenceSummary));
        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                defaultString(tenantId, "unknown"), defaultString(userId, "anonymous"), projectId, null, null
        );
        try {
            CreatorAiService.MeteredAiResponse aiResponse = creatorAiService.generateMetered(PROMPT_TYPE, input, usageContext);
            creatorAiService.publishBillingDebit(PROMPT_TYPE, aiResponse, usageContext);
            return normalize(aiResponse.output());
        } catch (RuntimeException ex) {
            log.warn("Sound design critique failed, treating as WARN and skipping regeneration errorType={} errorMessage={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return skipped();
        }
    }

    private String renderedPrompt(Map<String, Object> soundDesignPlan, List<Map<String, Object>> shotSequenceSummary) {
        return """
                You are a sound designer/mix engineer reviewing a proposed music, ambience, and SFX plan for an ad before it reaches video generation. The mix standard for this pipeline is fixed and non-negotiable: dialogue must always read clearly first, music ducks under it, room tone and SFX stay sparse.

                Shot sequence (shot number, beat, emotion, planned ambient bed / sync hit description per shot):
                %s

                Proposed sound design plan JSON:
                %s

                Score:
                - musicEmotionalArcFitScore: does the planned music mood/genre/tempo actually track the emotional arc across the sequence, not just sound pleasant in isolation.
                - dialogueAudibilityScore: does the plan leave clear room for dialogue to stay intelligible against the planned bed - flag any planned music/SFX moment that would compete with a shot that has dialogue.
                - sfxRestraintScore: are sound effects/stings used sparingly and purposefully (transitions, key beats) rather than constantly, which would read as amateurish.

                Return strict JSON only, no markdown, in exactly this shape:
                {
                  "status": "PASS|WARN|FAIL",
                  "confidence": 0.0,
                  "musicEmotionalArcFitScore": 0,
                  "dialogueAudibilityScore": 0,
                  "sfxRestraintScore": 0,
                  "issues": [],
                  "summary": ""
                }

                status is FAIL only for a real, fixable problem (music that would drown dialogue, a mood mismatch with the arc, SFX overload). Use WARN for usable-but-imperfect, PASS otherwise.
                """.formatted(toJson(shotSequenceSummary), toJson(soundDesignPlan));
    }

    @SuppressWarnings("unchecked")
    private SoundDesignCriticResult normalize(Map<String, Object> output) {
        Map<String, Object> safeOutput = output == null ? Map.of() : output;
        String status = normalizeStatus(stringValue(safeOutput.get("status")));
        double confidence = clamp(doubleValue(safeOutput.get("confidence"), 0.5), 0.0, 1.0);
        int musicFit = clampInt(intValue(safeOutput.get("musicEmotionalArcFitScore")), 0, 100);
        int audibility = clampInt(intValue(safeOutput.get("dialogueAudibilityScore")), 0, 100);
        int restraint = clampInt(intValue(safeOutput.get("sfxRestraintScore")), 0, 100);
        List<String> issues = stringList(safeOutput.get("issues"));
        String summary = stringValue(safeOutput.get("summary"));
        double average = (musicFit + audibility + restraint) / 3.0;
        return new SoundDesignCriticResult(status, confidence, musicFit, audibility, restraint, issues, summary, average);
    }

    private SoundDesignCriticResult skipped() {
        return new SoundDesignCriticResult("WARN", 0.0, 0, 0, 0, List.of(), "Critique skipped.", 0.0);
    }

    private String normalizeStatus(String raw) {
        String normalized = defaultString(raw, "").toUpperCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "PASS", "PASSED", "OK", "COMPLETED" -> "PASS";
            case "FAIL", "FAILED", "ERROR" -> "FAIL";
            default -> "WARN";
        };
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String text = stringValue(item);
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }
}
