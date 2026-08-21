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
 * Plans the missing "sound designer" crew role: an aggregated music/ambience/SFX plan for the
 * whole approved shot sequence, scored against the emotional arc and the pipeline's fixed
 * dialogue-first mix standard - runs alongside EditingPlanningService, after Phase 1 (shot plan)
 * and Phase 2 (storyboard frames) are approved.
 *
 * scriptPayload.soundDesignPlan is an existing read-only-consumed field (ScreenplayVideoService
 * reads request.get("soundDesignPlan")/scriptPayload.get("soundDesignPlan") as a fallback source
 * for backgroundMusic/ambience/soundEffects/voiceDialogue/audioMixStandards at render time - see
 * ScreenplayVideoService.java withAudioMixStandards()/soundDesignPlan usages) but nothing
 * generates it today - this service makes it real, the same gap EditingPlanningService closed for
 * scriptPayload.editingPlan. Persists as an additive merge (existing keys win only if this
 * service didn't produce a value for them), never a full overwrite - audioMixStandards in
 * particular is intentionally left alone here since ScreenplayVideoService already applies its
 * own hardcoded default for that key at render time.
 */
@Service
public class SoundDesignPlanningService {

    private static final Logger log = LoggerFactory.getLogger(SoundDesignPlanningService.class);
    private static final String PROMPT_TYPE = "SOUND_DESIGN_PLAN_GENERATE";
    private static final int MAX_ATTEMPTS = 3;

    private final CreatorAiService creatorAiService;
    private final CreatorScriptRepository scriptRepository;
    private final CreatorScriptShotPlanRepository shotPlanRepository;
    private final SoundDesignCriticService soundDesignCriticService;
    private final ObjectMapper objectMapper;

    public SoundDesignPlanningService(
            CreatorAiService creatorAiService,
            CreatorScriptRepository scriptRepository,
            CreatorScriptShotPlanRepository shotPlanRepository,
            SoundDesignCriticService soundDesignCriticService,
            ObjectMapper objectMapper
    ) {
        this.creatorAiService = creatorAiService;
        this.scriptRepository = scriptRepository;
        this.shotPlanRepository = shotPlanRepository;
        this.soundDesignCriticService = soundDesignCriticService;
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
            log.info("Skipping sound design plan generation - no shot plans exist yet scriptId={}", scriptId);
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
            SoundDesignCriticService.SoundDesignCriticResult critique = soundDesignCriticService.critique(attemptPlan, shotSummary, tenantId, userId, projectId);
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
        Map<String, Object> soundDesignPlan = new LinkedHashMap<>(mapValue(scriptPayload.get("soundDesignPlan")));
        best.forEach(soundDesignPlan::putIfAbsent);
        soundDesignPlan.put("generatedAt", OffsetDateTime.now().toString());
        scriptPayload.put("soundDesignPlan", soundDesignPlan);
        script.setScriptPayload(scriptPayload);
        scriptRepository.save(script);
        return soundDesignPlan;
    }

    private List<Map<String, Object>> shotSequenceSummary(List<CreatorScriptShotPlan> plans) {
        List<Map<String, Object>> summary = new ArrayList<>();
        for (CreatorScriptShotPlan plan : plans) {
            StoryboardTagView tag = ShotPlanTagMapper.storyboardTag(plan.getStoryboardTag(), objectMapper);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shotNumber", plan.getShotNumber());
            row.put("beatTitle", tag.beatTitle());
            row.put("emotion", tag.emotion());
            row.put("emotionIntensity", tag.emotionIntensityOrZero());
            row.put("ambientBedDescription", tag.ambientBedDescription());
            row.put("syncHitDescription", tag.syncHitDescription());
            row.put("hasDialogue", tag.primaryDialogue() != null && !tag.primaryDialogue().isSilent());
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
            log.warn("Sound design plan generation failed errorType={} errorMessage={}", ex.getClass().getSimpleName(), ex.getMessage());
            return Map.of();
        }
    }

    private String renderedPrompt(List<Map<String, Object>> shotSummary, String priorFeedback) {
        String feedbackSection = priorFeedback == null || priorFeedback.isBlank()
                ? ""
                : "\nA prior attempt was reviewed and rejected. Fix these specific issues this time:\n%s\n".formatted(priorFeedback);
        return """
                You are a sound designer planning the music, ambience, and SFX for this approved shot sequence before video generation runs. The pipeline's mix standard is fixed: dialogue must always read clearly first, music ducks under it, room tone and SFX stay sparse - plan within that constraint, don't fight it.

                Shot sequence (shot number, beat, emotion, planned ambient bed / sync hit description, whether the shot has dialogue):
                %s
                %s

                Decide: overall background music mood/genre/tempo direction that tracks the emotional arc across the sequence, an ambience/room-tone description, a short list of SFX/sync-hit cue points tied to specific shot numbers (use sparingly, only where they earn their place), and one guidance note for how music should duck under shots that have dialogue.

                Return strict JSON only, no markdown, in exactly this shape:
                {
                  "backgroundMusic": {"mood": "", "genre": "", "tempoNote": ""},
                  "ambience": {"prompt": ""},
                  "soundEffects": {"prompt": "", "cueList": [{"shotNumber": 1, "cueType": "sting|whoosh|impact", "note": ""}]},
                  "voiceDialogue": {"duckingGuidance": ""}
                }
                """.formatted(toJson(shotSummary), feedbackSection);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> planFrom(Map<String, Object> output) {
        if (output == null || output.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        if (output.get("backgroundMusic") instanceof Map<?, ?> backgroundMusic) {
            plan.put("backgroundMusic", backgroundMusic);
        }
        if (output.get("ambience") instanceof Map<?, ?> ambience) {
            plan.put("ambience", ambience);
        }
        if (output.get("soundEffects") instanceof Map<?, ?> soundEffects) {
            plan.put("soundEffects", soundEffects);
        }
        if (output.get("voiceDialogue") instanceof Map<?, ?> voiceDialogue) {
            plan.put("voiceDialogue", voiceDialogue);
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
