package com.dalai.llama.creator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plans the hook + beat structure BEFORE any script prose gets written - matches the real Shorts
 * pipeline precedent (STORY_BEAT_PLANNING/HOOK_GENERATION/HOOK_CRITIC are their own dedicated
 * stages there, not a scoring dimension bolted onto a later critic). Cheaper to critique/retry
 * than a full script, and makes the downstream script generation more likely to be right the
 * first time since it writes prose to already-approved structure instead of inventing structure
 * and prose together.
 *
 * Called synchronously from IdeaService's two script-generation entry points, right before the
 * STORY_SCRIPT_GENERATE/SCRIPT_GENERATE prompt is rendered - the approved plan is threaded in as
 * the new {{beatPlan}} placeholder (see V67 migration). Never blocks script generation: on total
 * failure this returns an empty map and the template's beatPlan section renders blank, exactly
 * like every other critic/planner in this graph.
 */
@Service
public class HookBeatPlanningService {

    private static final Logger log = LoggerFactory.getLogger(HookBeatPlanningService.class);
    private static final String PROMPT_TYPE = "HOOK_BEAT_PLAN_GENERATE";
    private static final int MAX_ATTEMPTS = 3;

    private final CreatorAiService creatorAiService;
    private final HookBeatCriticService hookBeatCriticService;
    private final ObjectMapper objectMapper;

    public HookBeatPlanningService(
            CreatorAiService creatorAiService,
            HookBeatCriticService hookBeatCriticService,
            ObjectMapper objectMapper
    ) {
        this.creatorAiService = creatorAiService;
        this.hookBeatCriticService = hookBeatCriticService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> generateBeatPlan(
            String ideaText,
            String categoryCode,
            String tone,
            int durationSeconds,
            Map<String, Object> productBrief,
            String tenantId,
            String userId,
            UUID projectId
    ) {
        if (ideaText == null || ideaText.isBlank()) {
            return Map.of();
        }
        Map<String, Object> best = null;
        double bestScore = -1;
        String priorFeedback = "";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Map<String, Object> attemptPlan = generatePlan(ideaText, categoryCode, tone, durationSeconds, productBrief, priorFeedback, tenantId, userId, projectId);
            if (attemptPlan.isEmpty()) {
                break;
            }
            HookBeatCriticService.HookBeatCriticResult critique = hookBeatCriticService.critique(attemptPlan, ideaText, tenantId, userId, projectId);
            if (critique.averageScore() > bestScore) {
                best = attemptPlan;
                bestScore = critique.averageScore();
            }
            if (!critique.isFail()) {
                break;
            }
            priorFeedback = critique.issues().isEmpty() ? critique.summary() : String.join("; ", critique.issues());
        }
        return best == null ? Map.of() : best;
    }

    private Map<String, Object> generatePlan(
            String ideaText,
            String categoryCode,
            String tone,
            int durationSeconds,
            Map<String, Object> productBrief,
            String priorFeedback,
            String tenantId,
            String userId,
            UUID projectId
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("renderedPrompt", renderedPrompt(ideaText, categoryCode, tone, durationSeconds, productBrief, priorFeedback));
        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                defaultString(tenantId, "unknown"), defaultString(userId, "anonymous"), projectId, null, null
        );
        try {
            CreatorAiService.MeteredAiResponse aiResponse = creatorAiService.generateMetered(PROMPT_TYPE, input, usageContext);
            creatorAiService.publishBillingDebit(PROMPT_TYPE, aiResponse, usageContext);
            return planFrom(aiResponse.output());
        } catch (RuntimeException ex) {
            log.warn("Hook/beat plan generation failed errorType={} errorMessage={}", ex.getClass().getSimpleName(), ex.getMessage());
            return Map.of();
        }
    }

    private String renderedPrompt(
            String ideaText,
            String categoryCode,
            String tone,
            int durationSeconds,
            Map<String, Object> productBrief,
            String priorFeedback
    ) {
        String feedbackSection = priorFeedback == null || priorFeedback.isBlank()
                ? ""
                : "\nA prior attempt was reviewed and rejected. Fix these specific issues this time:\n%s\n".formatted(priorFeedback);
        boolean productLed = productBrief != null && !productBrief.isEmpty();
        return """
                You are a showrunner planning the hook and beat structure for a %d-second video, BEFORE any script prose gets written. Category: %s. Tone: %s.

                Idea:
                %s
                %s
                %s

                Plan (do not write prose/dialogue yet, just structure): a hook line (what the first 2-3 seconds actually say/show to earn attention - specific, not generic), then an ordered list of beats. Each beat needs: a short title, its narrative purpose, its emotional target, how it escalates from the previous beat (what's new/higher-stakes/deeper), and its payoff if it's the final beat.

                Return strict JSON only, no markdown, in exactly this shape:
                {
                  "hookLine": "",
                  "beats": [{"title": "", "purpose": "", "emotionalTarget": "", "escalationFromPrevious": "", "payoff": ""}]
                }
                """.formatted(
                        Math.max(1, durationSeconds),
                        blankToNone(categoryCode),
                        blankToNone(tone),
                        ideaText,
                        productLed ? "\nProduct context (this is a product-led ad - the hook and beats should serve the product story, not ignore it):\n" + toJson(productBrief) : "",
                        feedbackSection
                );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> planFrom(Map<String, Object> output) {
        if (output == null || output.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("hookLine", stringValue(output.get("hookLine")));
        if (output.get("beats") instanceof List<?> beats && !beats.isEmpty()) {
            plan.put("beats", beats);
        } else {
            return Map.of();
        }
        return plan;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String blankToNone(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
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
