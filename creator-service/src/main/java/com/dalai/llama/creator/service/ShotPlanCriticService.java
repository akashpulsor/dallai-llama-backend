package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import com.dalai.llama.creator.dto.shotplan.ShotPlanTagMapper;
import com.dalai.llama.creator.dto.shotplan.StoryboardTagView;
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
 * Scores a script's full set of generated {@link CreatorScriptShotPlan} rows against every
 * creative dimension already present in storyboardTag/lightingBuildSheetTag/cameraPlanSheetTag -
 * not just shot-type variety. Two-part critic matching the Shorts pipeline's real split between
 * deterministic and AI-backed judges: variety/coverage are checked in Java (free, instant, same
 * pattern as ShortVisualCriticService), lighting/background/beat/emotion/dialogue/ingredient
 * grounding need one Gemini call per script (not per shot, so the critic judges sequence-level
 * coherence, not one shot in isolation).
 */
@Service
public class ShotPlanCriticService {

    private static final Logger log = LoggerFactory.getLogger(ShotPlanCriticService.class);
    private static final String PROMPT_TYPE = "SHOT_PLAN_CRITIC";

    private final CreatorAiService creatorAiService;
    private final ObjectMapper objectMapper;

    public ShotPlanCriticService(CreatorAiService creatorAiService, ObjectMapper objectMapper) {
        this.creatorAiService = creatorAiService;
        this.objectMapper = objectMapper;
    }

    public record ShotAudit(int shotNumber, String status, List<String> issues) {
    }

    public record ShotPlanCriticResult(
            String status,
            double confidence,
            int lightingDesignScore,
            int backgroundSetDesignScore,
            int beatFidelityScore,
            int emotionalArcScore,
            int dialogueScore,
            int ingredientGroundingScore,
            List<ShotAudit> shotAudits,
            List<String> deterministicIssues,
            String summary,
            double averageScore
    ) {
        /** Shot numbers either the deterministic pass or the AI pass flagged as FAIL. */
        public List<Integer> failedShotNumbers() {
            List<Integer> failed = new ArrayList<>();
            for (ShotAudit audit : shotAudits) {
                if ("FAIL".equals(audit.status())) {
                    failed.add(audit.shotNumber());
                }
            }
            return failed;
        }
    }

    public ShotPlanCriticResult critique(
            List<CreatorScriptShotPlan> plans,
            String ingredientDetails,
            String tenantId,
            String userId,
            UUID projectId
    ) {
        List<CreatorScriptShotPlan> sorted = plans == null
                ? List.of()
                : plans.stream().sorted((a, b) -> Integer.compare(shotNumber(a), shotNumber(b))).toList();
        if (sorted.isEmpty()) {
            return skipped();
        }

        List<String> deterministicIssues = new ArrayList<>();
        Map<Integer, List<String>> perShotDeterministicIssues = new LinkedHashMap<>();
        checkShotTypeVariety(sorted, deterministicIssues, perShotDeterministicIssues);
        checkCoverageCompleteness(sorted, deterministicIssues, perShotDeterministicIssues);

        ShotPlanCriticResult aiResult = critiqueWithAi(sorted, ingredientDetails, tenantId, userId, projectId);

        // Merge: a shot the deterministic pass flags is FAIL regardless of what the AI said -
        // deterministic checks are ground truth (variety/coverage are directly verifiable facts,
        // not a judgment call), AI checks add the dimensions that genuinely need creative judgment.
        List<ShotAudit> mergedAudits = new ArrayList<>();
        Map<Integer, ShotAudit> aiAuditsByShot = new LinkedHashMap<>();
        for (ShotAudit audit : aiResult.shotAudits()) {
            aiAuditsByShot.put(audit.shotNumber(), audit);
        }
        for (CreatorScriptShotPlan plan : sorted) {
            int number = shotNumber(plan);
            List<String> detIssues = perShotDeterministicIssues.getOrDefault(number, List.of());
            ShotAudit aiAudit = aiAuditsByShot.get(number);
            String status = !detIssues.isEmpty() ? "FAIL" : aiAudit == null ? "PASS" : aiAudit.status();
            List<String> issues = new ArrayList<>(detIssues);
            if (aiAudit != null) {
                issues.addAll(aiAudit.issues());
            }
            mergedAudits.add(new ShotAudit(number, status, issues));
        }

        String overallStatus = !deterministicIssues.isEmpty() ? "FAIL" : aiResult.status();
        return new ShotPlanCriticResult(
                overallStatus,
                aiResult.confidence(),
                aiResult.lightingDesignScore(),
                aiResult.backgroundSetDesignScore(),
                aiResult.beatFidelityScore(),
                aiResult.emotionalArcScore(),
                aiResult.dialogueScore(),
                aiResult.ingredientGroundingScore(),
                mergedAudits,
                deterministicIssues,
                aiResult.summary(),
                aiResult.averageScore()
        );
    }

    private void checkShotTypeVariety(
            List<CreatorScriptShotPlan> sorted,
            List<String> issues,
            Map<Integer, List<String>> perShotIssues
    ) {
        for (int i = 1; i < sorted.size(); i++) {
            String previous = storyboardTag(sorted.get(i - 1)).productShotType();
            String current = storyboardTag(sorted.get(i)).productShotType();
            if (previous.isBlank() || current.isBlank()) {
                continue;
            }
            if (previous.equalsIgnoreCase(current)) {
                int shotNumber = shotNumber(sorted.get(i));
                String issue = "Shot " + shotNumber(sorted.get(i - 1)) + " and shot " + shotNumber + " both assigned \"" + current + "\" - adjacent shots should use different shot types.";
                issues.add(issue);
                perShotIssues.computeIfAbsent(shotNumber, key -> new ArrayList<>()).add(issue);
            }
        }
    }

    private void checkCoverageCompleteness(
            List<CreatorScriptShotPlan> sorted,
            List<String> issues,
            Map<Integer, List<String>> perShotIssues
    ) {
        for (CreatorScriptShotPlan plan : sorted) {
            StoryboardTagView storyboardTag = storyboardTag(plan);
            int shotNumber = shotNumber(plan);
            List<String> missing = new ArrayList<>();
            if (storyboardTag.lightingAtmosphericDescription().isBlank()) {
                missing.add("lightingAtmosphericDescription");
            }
            if (storyboardTag.cameraAngle().isBlank() && storyboardTag.cameraMovement().isBlank()) {
                missing.add("camera direction");
            }
            boolean hasPrimaryCharacters = !storyboardTag.primaryCharacters().isEmpty();
            boolean hasAction = !storyboardTag.action().isBlank();
            if (!hasPrimaryCharacters && !hasAction) {
                missing.add("neither a character nor an action is described");
            }
            if (!missing.isEmpty()) {
                String issue = "Shot " + shotNumber + " is missing: " + String.join(", ", missing) + ".";
                issues.add(issue);
                perShotIssues.computeIfAbsent(shotNumber, key -> new ArrayList<>()).add(issue);
            }
        }
    }

    private StoryboardTagView storyboardTag(CreatorScriptShotPlan plan) {
        return ShotPlanTagMapper.storyboardTag(plan.getStoryboardTag(), objectMapper);
    }

    private ShotPlanCriticResult critiqueWithAi(
            List<CreatorScriptShotPlan> sorted,
            String ingredientDetails,
            String tenantId,
            String userId,
            UUID projectId
    ) {
        // Typed views are for THIS method's own reasoning (deterministic checks above already
        // use them) - the prompt itself still sends the original persisted maps so the AI sees
        // every field verbatim, including anything a future schema change adds that the views
        // don't model yet (see ShotPlanTagMapper's read-view-not-storage-model contract).
        List<Map<String, Object>> shotsForPrompt = new ArrayList<>();
        for (CreatorScriptShotPlan plan : sorted) {
            Map<String, Object> shot = new LinkedHashMap<>();
            shot.put("shotNumber", shotNumber(plan));
            shot.put("storyboardTag", plan.getStoryboardTag());
            shot.put("lightingBuildSheetTag", plan.getLightingBuildSheetTag());
            shot.put("cameraPlanSheetTag", plan.getCameraPlanSheetTag());
            shotsForPrompt.add(shot);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("renderedPrompt", renderedPrompt(shotsForPrompt, ingredientDetails));
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
            log.warn("Shot plan critique failed, treating as WARN and skipping regeneration errorType={} errorMessage={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return skipped();
        }
    }

    private String renderedPrompt(List<Map<String, Object>> shots, String ingredientDetails) {
        return """
                You are a Hollywood director + DP reviewing a full shot sequence's production plan before it goes to storyboard image and video generation.

                Shot sequence JSON (storyboardTag/lightingBuildSheetTag/cameraPlanSheetTag per shot):
                %s

                Ingredient/product evidence available for this script (if blank, this is not a product-led script - ingredientGroundingScore should be 100):
                %s

                Score the sequence as a whole (not shot-by-shot in isolation) on:
                - lightingDesignScore: is lightingAtmosphericDescription/keyLightSourceLabel an intentional, mood-appropriate design choice per shot (not generic/flat), and does the lighting floor plan in lightingBuildSheetTag actually support it.
                - backgroundSetDesignScore: is setDesign/environment/culturalReferences specific and appropriate to the product/brand/audience, not a generic backdrop.
                - beatFidelityScore: does narrativeBeatSummary/beatTitle progression across shots actually build (hook shot lands, beats escalate, doesn't plateau or repeat).
                - emotionalArcScore: does emotion/emotionIntensity progress sensibly shot-to-shot rather than being flat or randomly jumping.
                - dialogueScore: is primaryDialogue natural, on-character, and non-redundant across shots.
                - ingredientGroundingScore: flags invented ingredients/claims not present in the evidence above.

                Also return a per-shot audit: for any shot with a real, specific problem in one of the dimensions above, set that shot's status to FAIL and list the issue; otherwise PASS or WARN for minor/fixable notes.

                Return strict JSON only, no markdown, in exactly this shape:
                {
                  "status": "PASS|WARN|FAIL",
                  "confidence": 0.0,
                  "lightingDesignScore": 0,
                  "backgroundSetDesignScore": 0,
                  "beatFidelityScore": 0,
                  "emotionalArcScore": 0,
                  "dialogueScore": 0,
                  "ingredientGroundingScore": 0,
                  "shotAudits": [{"shotNumber": 1, "status": "PASS|WARN|FAIL", "issues": []}],
                  "summary": ""
                }

                status is FAIL only when the sequence as a whole has a real, fixable problem worth regenerating shots for. Use WARN for usable-but-imperfect, PASS otherwise.
                """.formatted(toJson(shots), ingredientDetails == null || ingredientDetails.isBlank() ? "(none)" : ingredientDetails);
    }

    @SuppressWarnings("unchecked")
    private ShotPlanCriticResult normalize(Map<String, Object> output) {
        Map<String, Object> safeOutput = output == null ? Map.of() : output;
        String status = normalizeStatus(stringValue(safeOutput.get("status")));
        double confidence = clamp(doubleValue(safeOutput.get("confidence"), 0.5), 0.0, 1.0);
        int lightingDesignScore = clampInt(intValue(safeOutput.get("lightingDesignScore")), 0, 100);
        int backgroundSetDesignScore = clampInt(intValue(safeOutput.get("backgroundSetDesignScore")), 0, 100);
        int beatFidelityScore = clampInt(intValue(safeOutput.get("beatFidelityScore")), 0, 100);
        int emotionalArcScore = clampInt(intValue(safeOutput.get("emotionalArcScore")), 0, 100);
        int dialogueScore = clampInt(intValue(safeOutput.get("dialogueScore")), 0, 100);
        int ingredientGroundingScore = clampInt(intValue(safeOutput.get("ingredientGroundingScore")), 0, 100);
        String summary = stringValue(safeOutput.get("summary"));

        List<ShotAudit> audits = new ArrayList<>();
        if (safeOutput.get("shotAudits") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> audit = (Map<String, Object>) map;
                audits.add(new ShotAudit(
                        intValue(audit.get("shotNumber")),
                        normalizeStatus(stringValue(audit.get("status"))),
                        stringList(audit.get("issues"))
                ));
            }
        }
        double average = (lightingDesignScore + backgroundSetDesignScore + beatFidelityScore
                + emotionalArcScore + dialogueScore + ingredientGroundingScore) / 6.0;
        return new ShotPlanCriticResult(
                status, confidence, lightingDesignScore, backgroundSetDesignScore, beatFidelityScore,
                emotionalArcScore, dialogueScore, ingredientGroundingScore, audits, List.of(), summary, average
        );
    }

    private ShotPlanCriticResult skipped() {
        return new ShotPlanCriticResult("WARN", 0.0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), "Critique skipped.", 0.0);
    }

    private int shotNumber(CreatorScriptShotPlan plan) {
        return plan.getShotNumber() == null ? 0 : plan.getShotNumber();
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
