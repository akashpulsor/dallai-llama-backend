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
 * Scores an EditingPlanningService-generated cut/transition/pacing plan against the approved
 * shot sequence. Text-only critic (no image), follows the same generateMetered + JSON-contract
 * pattern as CampaignAngleCriticService/ShotPlanCriticService's AI component.
 */
@Service
public class EditingPlanCriticService {

    private static final Logger log = LoggerFactory.getLogger(EditingPlanCriticService.class);
    private static final String PROMPT_TYPE = "EDITING_PLAN_CRITIC";

    private final CreatorAiService creatorAiService;
    private final ObjectMapper objectMapper;

    public EditingPlanCriticService(CreatorAiService creatorAiService, ObjectMapper objectMapper) {
        this.creatorAiService = creatorAiService;
        this.objectMapper = objectMapper;
    }

    public record EditingPlanCriticResult(
            String status,
            double confidence,
            int pacingCoherenceScore,
            int transitionAppropriatenessScore,
            int continuityScore,
            List<String> issues,
            String summary,
            double averageScore
    ) {
        public boolean isFail() {
            return "FAIL".equals(status);
        }
    }

    public EditingPlanCriticResult critique(
            Map<String, Object> editingPlan,
            List<Map<String, Object>> shotSequenceSummary,
            String tenantId,
            String userId,
            UUID projectId
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("renderedPrompt", renderedPrompt(editingPlan, shotSequenceSummary));
        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                defaultString(tenantId, "unknown"), defaultString(userId, "anonymous"), projectId, null, null
        );
        try {
            CreatorAiService.MeteredAiResponse aiResponse = creatorAiService.generateMetered(PROMPT_TYPE, input, usageContext);
            creatorAiService.publishBillingDebit(PROMPT_TYPE, aiResponse, usageContext);
            return normalize(aiResponse.output());
        } catch (RuntimeException ex) {
            log.warn("Editing plan critique failed, treating as WARN and skipping regeneration errorType={} errorMessage={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return skipped();
        }
    }

    private String renderedPrompt(Map<String, Object> editingPlan, List<Map<String, Object>> shotSequenceSummary) {
        return """
                You are a supervising editor reviewing a proposed cut/transition/pacing plan against the approved shot sequence, before it reaches video generation.

                Shot sequence (shot number, beat, emotion, productShotType, cameraMovement per shot):
                %s

                Proposed editing plan JSON:
                %s

                Score:
                - pacingCoherenceScore: does the plan's pacing rhythm actually track the emotional arc across the sequence (speed up/slow down where the story calls for it), not just "is it a reasonable rhythm in isolation".
                - transitionAppropriatenessScore: does each transition choice (cut/match-cut/dissolve/etc.) serve the story beat it sits on, not arbitrary or repeated without reason.
                - continuityScore: is a match-cut ever proposed between two shots whose framing/camera movement/beat make that cut impossible or confusing - flag any transition that doesn't actually fit the two shots it connects.

                Return strict JSON only, no markdown, in exactly this shape:
                {
                  "status": "PASS|WARN|FAIL",
                  "confidence": 0.0,
                  "pacingCoherenceScore": 0,
                  "transitionAppropriatenessScore": 0,
                  "continuityScore": 0,
                  "issues": [],
                  "summary": ""
                }

                status is FAIL only for a real, fixable problem (a continuity-breaking transition, pacing that ignores the arc entirely). Use WARN for usable-but-imperfect, PASS otherwise.
                """.formatted(toJson(shotSequenceSummary), toJson(editingPlan));
    }

    @SuppressWarnings("unchecked")
    private EditingPlanCriticResult normalize(Map<String, Object> output) {
        Map<String, Object> safeOutput = output == null ? Map.of() : output;
        String status = normalizeStatus(stringValue(safeOutput.get("status")));
        double confidence = clamp(doubleValue(safeOutput.get("confidence"), 0.5), 0.0, 1.0);
        int pacing = clampInt(intValue(safeOutput.get("pacingCoherenceScore")), 0, 100);
        int transitions = clampInt(intValue(safeOutput.get("transitionAppropriatenessScore")), 0, 100);
        int continuity = clampInt(intValue(safeOutput.get("continuityScore")), 0, 100);
        List<String> issues = stringList(safeOutput.get("issues"));
        String summary = stringValue(safeOutput.get("summary"));
        double average = (pacing + transitions + continuity) / 3.0;
        return new EditingPlanCriticResult(status, confidence, pacing, transitions, continuity, issues, summary, average);
    }

    private EditingPlanCriticResult skipped() {
        return new EditingPlanCriticResult("WARN", 0.0, 0, 0, 0, List.of(), "Critique skipped.", 0.0);
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
