package com.dalai.llama.creator.service;

import com.dalai.llama.creator.dto.response.CampaignAngleSuggestionResponse;
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
 * Scores the 3 campaign angles {@link CampaignAngleSuggestionService} generates, following this
 * codebase's established critic JSON convention (status PASS/WARN/FAIL, confidence 0.0-1.0, named
 * *Score fields 0-100, issues, summary - see GoogleShortStoryCriticService/GoogleShortSceneCriticService).
 * A weak batch (status FAIL) is regenerated once by the caller with this critic's feedback folded in.
 */
@Service
public class CampaignAngleCriticService {

    private static final Logger log = LoggerFactory.getLogger(CampaignAngleCriticService.class);
    private static final String PROMPT_TYPE = "CAMPAIGN_ANGLE_CRITIC";

    private final CreatorAiService creatorAiService;
    private final ObjectMapper objectMapper;

    public CampaignAngleCriticService(CreatorAiService creatorAiService, ObjectMapper objectMapper) {
        this.creatorAiService = creatorAiService;
        this.objectMapper = objectMapper;
    }

    public record AngleAudit(
            String angleId,
            int hookStrengthScore,
            int briefAlignmentScore,
            int visualDirectionSpecificityScore,
            List<String> issues
    ) {
    }

    public record CampaignAngleCriticResult(
            String status,
            double confidence,
            List<AngleAudit> angleAudits,
            String summary,
            double averageScore
    ) {
    }

    public CampaignAngleCriticResult critique(
            List<CampaignAngleSuggestionResponse.CampaignAngle> angles,
            Map<String, Object> brief,
            String tenantId,
            String userId,
            UUID projectId
    ) {
        if (angles == null || angles.isEmpty()) {
            return skipped();
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("renderedPrompt", renderedPrompt(angles, brief));
        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                defaultString(tenantId, "unknown"),
                defaultString(userId, "anonymous"),
                projectId,
                null,
                null
        );
        try {
            CreatorAiService.MeteredAiResponse aiResponse = creatorAiService.generateMetered(PROMPT_TYPE, input, usageContext);
            creatorAiService.publishBillingDebit(PROMPT_TYPE, aiResponse, usageContext);
            return normalize(aiResponse.output());
        } catch (RuntimeException ex) {
            log.warn("Campaign angle critique failed, treating as WARN and skipping regeneration errorType={} errorMessage={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return skipped();
        }
    }

    private String renderedPrompt(List<CampaignAngleSuggestionResponse.CampaignAngle> angles, Map<String, Object> brief) {
        return """
                You are a senior creative strategist reviewing three campaign angles before they reach production.

                Original brief JSON:
                %s

                Angles to review JSON:
                %s

                Score each angle on:
                - hookStrengthScore: does the hook actually earn attention in the first seconds, or is it generic.
                - briefAlignmentScore: does it honor the product facts, campaign objective, and target audience in the brief.
                - visualDirectionSpecificityScore: is visualDirection a concrete, shootable direction, or vague filler that gives production nothing to work with.

                Return strict JSON only, no markdown, in exactly this shape:
                {
                  "status": "PASS|WARN|FAIL",
                  "confidence": 0.0,
                  "angleAudits": [
                    {"angleId": "angle-1", "hookStrengthScore": 0, "briefAlignmentScore": 0, "visualDirectionSpecificityScore": 0, "issues": []}
                  ],
                  "summary": ""
                }

                status is FAIL only when the batch as a whole is weak (most angles are generic, off-brief, or visually vague) and should be regenerated. Use WARN for a batch that is usable but has fixable weak spots, PASS otherwise.
                """.formatted(toJson(brief), toJson(angles));
    }

    @SuppressWarnings("unchecked")
    private CampaignAngleCriticResult normalize(Map<String, Object> output) {
        Map<String, Object> safeOutput = output == null ? Map.of() : output;
        String status = normalizeStatus(stringValue(safeOutput.get("status")));
        double confidence = clamp(doubleValue(safeOutput.get("confidence"), 0.5), 0.0, 1.0);
        String summary = stringValue(safeOutput.get("summary"));

        List<AngleAudit> audits = new ArrayList<>();
        Object rawAudits = safeOutput.get("angleAudits");
        if (rawAudits instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> audit = (Map<String, Object>) map;
                audits.add(new AngleAudit(
                        stringValue(audit.get("angleId")),
                        clampInt(intValue(audit.get("hookStrengthScore")), 0, 100),
                        clampInt(intValue(audit.get("briefAlignmentScore")), 0, 100),
                        clampInt(intValue(audit.get("visualDirectionSpecificityScore")), 0, 100),
                        stringList(audit.get("issues"))
                ));
            }
        }
        double average = audits.isEmpty()
                ? 0
                : audits.stream()
                        .mapToDouble(audit -> (audit.hookStrengthScore() + audit.briefAlignmentScore() + audit.visualDirectionSpecificityScore()) / 3.0)
                        .average()
                        .orElse(0);
        return new CampaignAngleCriticResult(status, confidence, audits, summary, average);
    }

    private CampaignAngleCriticResult skipped() {
        return new CampaignAngleCriticResult("WARN", 0.0, List.of(), "Critique skipped.", 0.0);
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
