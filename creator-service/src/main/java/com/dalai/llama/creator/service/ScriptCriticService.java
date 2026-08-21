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
 * Scores a generated story script (or screenplay) against the approved Phase 3a beat plan, plus
 * the creative dimensions that matter independent of any plan: emotional arc, dialogue quality,
 * narrative coherence/pacing, and brief/product alignment. Text-only critic, same generateMetered
 * + JSON-contract pattern as CampaignAngleCriticService/ShotPlanCriticService's AI component.
 *
 * planAdherenceScore is 100 when no beat plan was supplied (nothing to adhere to - this script
 * planned its own structure, which is a valid path when Phase 3a's planner returned nothing).
 */
@Service
public class ScriptCriticService {

    private static final Logger log = LoggerFactory.getLogger(ScriptCriticService.class);
    private static final String PROMPT_TYPE = "SCRIPT_CRITIC";

    private final CreatorAiService creatorAiService;
    private final ObjectMapper objectMapper;

    public ScriptCriticService(CreatorAiService creatorAiService, ObjectMapper objectMapper) {
        this.creatorAiService = creatorAiService;
        this.objectMapper = objectMapper;
    }

    public record ScriptCriticResult(
            String status,
            double confidence,
            int planAdherenceScore,
            int emotionalArcScore,
            int dialogueQualityScore,
            int narrativeCoherenceScore,
            int briefAlignmentScore,
            List<String> issues,
            String summary,
            double averageScore
    ) {
        public boolean isFail() {
            return "FAIL".equals(status);
        }
    }

    public ScriptCriticResult critique(
            Map<String, Object> scriptContent,
            Map<String, Object> beatPlan,
            Map<String, Object> productBrief,
            String tenantId,
            String userId,
            UUID projectId
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("renderedPrompt", renderedPrompt(scriptContent, beatPlan, productBrief));
        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                defaultString(tenantId, "unknown"), defaultString(userId, "anonymous"), projectId, null, null
        );
        try {
            CreatorAiService.MeteredAiResponse aiResponse = creatorAiService.generateMetered(PROMPT_TYPE, input, usageContext);
            creatorAiService.publishBillingDebit(PROMPT_TYPE, aiResponse, usageContext);
            return normalize(aiResponse.output(), beatPlan == null || beatPlan.isEmpty());
        } catch (RuntimeException ex) {
            log.warn("Script critique failed, treating as WARN and skipping regeneration errorType={} errorMessage={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return skipped();
        }
    }

    private String renderedPrompt(Map<String, Object> scriptContent, Map<String, Object> beatPlan, Map<String, Object> productBrief) {
        boolean hasBeatPlan = beatPlan != null && !beatPlan.isEmpty();
        boolean productLed = productBrief != null && !productBrief.isEmpty();
        return """
                You are a script editor reviewing a generated script/screenplay before it moves downstream to shot planning and video generation.

                Generated script/screenplay JSON:
                %s
                %s
                %s

                Score:
                - planAdherenceScore: %s
                - emotionalArcScore: does the character/audience emotional trajectory actually move across the script, matching any planned arc, rather than staying flat.
                - dialogueQualityScore: is dialogue natural, on-character, and advancing the story/product message rather than filler or generic lines.
                - narrativeCoherenceScore: does the script hold together - no contradictions, no dropped setups, reasonable pacing for its duration.
                - briefAlignmentScore: %s

                Return strict JSON only, no markdown, in exactly this shape:
                {
                  "status": "PASS|WARN|FAIL",
                  "confidence": 0.0,
                  "planAdherenceScore": 0,
                  "emotionalArcScore": 0,
                  "dialogueQualityScore": 0,
                  "narrativeCoherenceScore": 0,
                  "briefAlignmentScore": 0,
                  "issues": [],
                  "summary": ""
                }

                status is FAIL only for a real, fixable problem (drifts from the approved plan, flat/broken arc, filler dialogue, incoherent structure, ignores the product brief). Use WARN for usable-but-imperfect, PASS otherwise.
                """.formatted(
                        toJson(scriptContent),
                        hasBeatPlan ? "\nApproved beat plan this script should deliver:\n" + toJson(beatPlan) : "",
                        productLed ? "\nProduct brief this script should stay grounded in:\n" + toJson(productBrief) : "",
                        hasBeatPlan
                                ? "does the script actually deliver the approved beat plan above - hook lands as planned, beats appear in order without being skipped or flattened."
                                : "no beat plan was supplied for this script, so score 100 for this dimension.",
                        productLed
                                ? "does the script stay grounded in the product brief above without inventing claims not present in it."
                                : "no product brief applies to this script, so score 100 for this dimension."
                );
    }

    @SuppressWarnings("unchecked")
    private ScriptCriticResult normalize(Map<String, Object> output, boolean noBeatPlanSupplied) {
        Map<String, Object> safeOutput = output == null ? Map.of() : output;
        String status = normalizeStatus(stringValue(safeOutput.get("status")));
        double confidence = clamp(doubleValue(safeOutput.get("confidence"), 0.5), 0.0, 1.0);
        int planAdherence = noBeatPlanSupplied ? 100 : clampInt(intValue(safeOutput.get("planAdherenceScore")), 0, 100);
        int emotionalArc = clampInt(intValue(safeOutput.get("emotionalArcScore")), 0, 100);
        int dialogueQuality = clampInt(intValue(safeOutput.get("dialogueQualityScore")), 0, 100);
        int narrativeCoherence = clampInt(intValue(safeOutput.get("narrativeCoherenceScore")), 0, 100);
        int briefAlignment = clampInt(intValue(safeOutput.get("briefAlignmentScore")), 0, 100);
        List<String> issues = stringList(safeOutput.get("issues"));
        String summary = stringValue(safeOutput.get("summary"));
        double average = (planAdherence + emotionalArc + dialogueQuality + narrativeCoherence + briefAlignment) / 5.0;
        return new ScriptCriticResult(status, confidence, planAdherence, emotionalArc, dialogueQuality, narrativeCoherence, briefAlignment, issues, summary, average);
    }

    private ScriptCriticResult skipped() {
        return new ScriptCriticResult("WARN", 0.0, 0, 0, 0, 0, 0, List.of(), "Critique skipped.", 0.0);
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
