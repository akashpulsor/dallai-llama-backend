package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorScript;
import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import com.dalai.llama.creator.dto.shotplan.ShotPlanTagMapper;
import com.dalai.llama.creator.dto.shotplan.StoryboardTagView;
import com.dalai.llama.creator.repository.CreatorScriptRepository;
import com.dalai.llama.creator.repository.CreatorScriptShotPlanRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plans the missing "editor" crew role: per-shot-boundary transitions, overall pacing rhythm,
 * and sound-sync cue points for the whole approved shot sequence - runs after Phase 1 (shot
 * plan) and Phase 2 (storyboard frames) are approved. The approved plan merges into
 * CreatorScript.scriptPayload.editingPlan, which ScreenplayVideoService/
 * ScreenplayVideoProviderGenerationService already read as a fallback source for
 * providerRequest.editingPlan (see ScreenplayVideoService.java:7181) - so video generation picks
 * this up with no change needed to the prompt-building code itself.
 *
 * Deliberately additive to the existing editingPlan concept: that field already carries a
 * human-editor handoff checklist (editorNotes/recommendedTools/deliverables, built later at
 * render time by ScreenplayVideoService's refreshAudioPlan-style logic via putIfAbsent). This
 * service adds transitions/pacingRhythm/soundSyncCues alongside that, never replacing it.
 */
@Service
public class EditingPlanningService {

    private static final Logger log = LoggerFactory.getLogger(EditingPlanningService.class);
    private static final String PROMPT_TYPE = "EDITING_PLAN_GENERATE";
    private static final int MAX_ATTEMPTS = 3;

    private final CreatorAiService creatorAiService;
    private final CreatorScriptRepository scriptRepository;
    private final CreatorScriptShotPlanRepository shotPlanRepository;
    private final EditingPlanCriticService editingPlanCriticService;
    private final ObjectMapper objectMapper;

    public EditingPlanningService(
            CreatorAiService creatorAiService,
            CreatorScriptRepository scriptRepository,
            CreatorScriptShotPlanRepository shotPlanRepository,
            EditingPlanCriticService editingPlanCriticService,
            ObjectMapper objectMapper
    ) {
        this.creatorAiService = creatorAiService;
        this.scriptRepository = scriptRepository;
        this.shotPlanRepository = shotPlanRepository;
        this.editingPlanCriticService = editingPlanCriticService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> generateAndSave(UUID scriptId, String styleKey, String tenantId, String userId) {
        CreatorScript script = scriptRepository.findById(scriptId)
                .orElseThrow(() -> new IllegalStateException("Script not found: " + scriptId));
        List<CreatorScriptShotPlan> plans = shotPlanRepository.findByScriptIdOrderByShotNumberAsc(scriptId).stream()
                .filter(plan -> styleKey == null || styleKey.equals(plan.getStyleKey()))
                .toList();
        if (plans.isEmpty()) {
            log.info("Skipping editing plan generation - no shot plans exist yet scriptId={}", scriptId);
            return Map.of();
        }
        List<Map<String, Object>> shotSummary = shotSequenceSummary(plans);
        UUID projectId = script.getProjectId();

        Map<String, Object> best = null;
        double bestScore = -1;
        String priorFeedback = "";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Map<String, Object> attemptPlan = generatePlan(shotSummary, priorFeedback, tenantId, userId, projectId);
            if (attemptPlan.isEmpty()) {
                break;
            }
            EditingPlanCriticService.EditingPlanCriticResult critique = editingPlanCriticService.critique(attemptPlan, shotSummary, tenantId, userId, projectId);
            if (critique.averageScore() > bestScore) {
                best = attemptPlan;
                bestScore = critique.averageScore();
            }
            if (!critique.isFail()) {
                break;
            }
            priorFeedback = critique.issues().isEmpty() ? critique.summary() : String.join("; ", critique.issues());
        }
        if (best == null) {
            return Map.of();
        }

        Map<String, Object> scriptPayload = new LinkedHashMap<>(script.getScriptPayload() == null ? Map.of() : script.getScriptPayload());
        Map<String, Object> editingPlan = new LinkedHashMap<>(mapValue(scriptPayload.get("editingPlan")));
        editingPlan.putAll(best);
        editingPlan.put("generatedAt", OffsetDateTime.now().toString());
        scriptPayload.put("editingPlan", editingPlan);
        script.setScriptPayload(scriptPayload);
        scriptRepository.save(script);
        return editingPlan;
    }

    private List<Map<String, Object>> shotSequenceSummary(List<CreatorScriptShotPlan> plans) {
        List<Map<String, Object>> summary = new ArrayList<>();
        for (CreatorScriptShotPlan plan : plans) {
            StoryboardTagView tag = ShotPlanTagMapper.storyboardTag(plan.getStoryboardTag(), objectMapper);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shotNumber", plan.getShotNumber());
            row.put("beatTitle", tag.beatTitle());
            row.put("narrativeBeatSummary", tag.narrativeBeatSummary());
            row.put("emotion", tag.emotion());
            row.put("emotionIntensity", tag.emotionIntensityOrZero());
            row.put("productShotType", tag.productShotType());
            row.put("cameraMovement", tag.cameraMovement());
            row.put("durationSeconds", tag.durationSeconds());
            summary.add(row);
        }
        return summary;
    }

    private Map<String, Object> generatePlan(List<Map<String, Object>> shotSummary, String priorFeedback, String tenantId, String userId, UUID projectId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("renderedPrompt", renderedPrompt(shotSummary, priorFeedback));
        CreatorAiService.AiUsageContext usageContext = new CreatorAiService.AiUsageContext(
                defaultString(tenantId, "unknown"), defaultString(userId, "anonymous"), projectId, null, null
        );
        try {
            CreatorAiService.MeteredAiResponse aiResponse = creatorAiService.generateMetered(PROMPT_TYPE, input, usageContext);
            creatorAiService.publishBillingDebit(PROMPT_TYPE, aiResponse, usageContext);
            return planFrom(aiResponse.output());
        } catch (RuntimeException ex) {
            log.warn("Editing plan generation failed errorType={} errorMessage={}", ex.getClass().getSimpleName(), ex.getMessage());
            return Map.of();
        }
    }

    private String renderedPrompt(List<Map<String, Object>> shotSummary, String priorFeedback) {
        String feedbackSection = priorFeedback == null || priorFeedback.isBlank()
                ? ""
                : "\nA prior attempt was reviewed and rejected. Fix these specific issues this time:\n%s\n".formatted(priorFeedback);
        return """
                You are a supervising editor planning how this approved shot sequence should actually be cut together - transitions, pacing rhythm, and sound-sync points - before video generation runs.

                Shot sequence (shot number, beat, emotion, productShotType, cameraMovement, planned duration):
                %s
                %s

                For each shot boundary (between shot N and shot N+1), decide a transition type (cut, match_cut, dissolve, whip_pan, hard_cut_on_action, etc.) with a one-line reason tied to the beat progression - never pick match_cut between shots whose camera movement/framing can't actually match. Also decide an overall pacing rhythm description (where the edit should speed up or slow down relative to the emotional arc), and sound-sync cue points (moments a hit/sting/beat-drop should land, tied to specific shot numbers).

                Return strict JSON only, no markdown, in exactly this shape:
                {
                  "transitions": [{"afterShotNumber": 1, "transitionType": "cut", "reason": ""}],
                  "pacingRhythm": "one paragraph describing the intended rhythm across the sequence",
                  "soundSyncCues": [{"shotNumber": 1, "cueType": "sting|beat_drop|silence_beat", "note": ""}]
                }
                """.formatted(toJson(shotSummary), feedbackSection);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> planFrom(Map<String, Object> output) {
        if (output == null || output.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        if (output.get("transitions") instanceof List<?> transitions) {
            plan.put("transitions", transitions);
        } else {
            plan.put("transitions", List.of());
        }
        plan.put("pacingRhythm", stringValue(output.get("pacingRhythm")));
        if (output.get("soundSyncCues") instanceof List<?> cues) {
            plan.put("soundSyncCues", cues);
        } else {
            plan.put("soundSyncCues", List.of());
        }
        return plan;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    result.put(String.valueOf(key), item);
                }
            });
            return result;
        }
        return new LinkedHashMap<>();
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
