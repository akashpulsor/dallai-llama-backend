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
 * Scores a HookBeatPlanningService-generated beat sheet before any prose is written from it -
 * cheap (a few hundred tokens vs a full script) and catches structural problems (a hook that
 * doesn't land, beats that plateau instead of escalating) before they get baked into a full
 * script. Text-only critic, same generateMetered + JSON-contract pattern as
 * CampaignAngleCriticService.
 */
@Service
public class HookBeatCriticService {

    private static final Logger log = LoggerFactory.getLogger(HookBeatCriticService.class);
    private static final String PROMPT_TYPE = "HOOK_BEAT_CRITIC";

    private final CreatorAiService creatorAiService;
    private final ObjectMapper objectMapper;

    public HookBeatCriticService(CreatorAiService creatorAiService, ObjectMapper objectMapper) {
        this.creatorAiService = creatorAiService;
        this.objectMapper = objectMapper;
    }

    public record HookBeatCriticResult(
            String status,
            double confidence,
            int hookStrengthScore,
            int beatEscalationScore,
            int emotionalArcScore,
            List<String> issues,
            String summary,
            double averageScore
    ) {
        public boolean isFail() {
            return "FAIL".equals(status);
        }
    }

    public HookBeatCriticResult critique(
            Map<String, Object> beatPlan,
            String ideaText,
            String tenantId,
            String userId,
            UUID projectId
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("renderedPrompt", renderedPrompt(beatPlan, ideaText));
        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                defaultString(tenantId, "unknown"), defaultString(userId, "anonymous"), projectId, null, null
        );
        try {
            CreatorAiService.MeteredAiResponse aiResponse = creatorAiService.generateMetered(PROMPT_TYPE, input, usageContext);
            creatorAiService.publishBillingDebit(PROMPT_TYPE, aiResponse, usageContext);
            return normalize(aiResponse.output());
        } catch (RuntimeException ex) {
            log.warn("Hook/beat critique failed, treating as WARN and skipping regeneration errorType={} errorMessage={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return skipped();
        }
    }

    private String renderedPrompt(Map<String, Object> beatPlan, String ideaText) {
        return """
                You are a showrunner reviewing a proposed hook + beat sheet before any script prose is written from it.

                Original idea:
                %s

                Proposed hook/beat plan JSON:
                %s

                Score:
                - hookStrengthScore: does the hook line actually earn attention in the first couple seconds - specific and surprising, not generic ("You won't believe...").
                - beatEscalationScore: does each beat actually escalate from the previous one (raise stakes, add information, deepen emotion) rather than plateauing or repeating the same idea in different words.
                - emotionalArcScore: does the beat sequence's emotional targets move somewhere real by the payoff beat, not stay flat.

                Return strict JSON only, no markdown, in exactly this shape:
                {
                  "status": "PASS|WARN|FAIL",
                  "confidence": 0.0,
                  "hookStrengthScore": 0,
                  "beatEscalationScore": 0,
                  "emotionalArcScore": 0,
                  "issues": [],
                  "summary": ""
                }

                status is FAIL only for a real, fixable problem (weak/generic hook, a beat that repeats the prior beat, a flat arc). Use WARN for usable-but-imperfect, PASS otherwise.
                """.formatted(blankToNone(ideaText), toJson(beatPlan));
    }

    @SuppressWarnings("unchecked")
    private HookBeatCriticResult normalize(Map<String, Object> output) {
        Map<String, Object> safeOutput = output == null ? Map.of() : output;
        String status = normalizeStatus(stringValue(safeOutput.get("status")));
        double confidence = clamp(doubleValue(safeOutput.get("confidence"), 0.5), 0.0, 1.0);
        int hookStrength = clampInt(intValue(safeOutput.get("hookStrengthScore")), 0, 100);
        int beatEscalation = clampInt(intValue(safeOutput.get("beatEscalationScore")), 0, 100);
        int emotionalArc = clampInt(intValue(safeOutput.get("emotionalArcScore")), 0, 100);
        List<String> issues = stringList(safeOutput.get("issues"));
        String summary = stringValue(safeOutput.get("summary"));
        double average = (hookStrength + beatEscalation + emotionalArc) / 3.0;
        return new HookBeatCriticResult(status, confidence, hookStrength, beatEscalation, emotionalArc, issues, summary, average);
    }

    private HookBeatCriticResult skipped() {
        return new HookBeatCriticResult("WARN", 0.0, 0, 0, 0, List.of(), "Critique skipped.", 0.0);
    }

    private String normalizeStatus(String raw) {
        String normalized = defaultString(raw, "").toUpperCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "PASS", "PASSED", "OK", "COMPLETED" -> "PASS";
            case "FAIL", "FAILED", "ERROR" -> "FAIL";
            default -> "WARN";
        };
    }

    private String blankToNone(String value) {
        return value == null || value.isBlank() ? "(none supplied)" : value;
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
