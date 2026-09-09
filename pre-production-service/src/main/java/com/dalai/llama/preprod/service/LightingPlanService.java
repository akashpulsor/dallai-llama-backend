package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.LightingPlan;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotProductReference;
import com.dalai.llama.preprod.dto.LightingPlanView;
import com.dalai.llama.preprod.dto.SaveLightingPlanEditRequest;
import com.dalai.llama.preprod.repository.LightingPlanRepository;
import com.dalai.llama.preprod.repository.ShotProductReferenceRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.generation.LightingPlanGenerationResult;
import com.dalai.llama.preprod.service.generation.PlanCritiqueResult;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Plans a shot's lighting build sheet -- feeds the LIGHTING {@code ShotImageKind}'s prompt with
 * real structure (6 gear slots, ordered setup steps) instead of just the shot's flat
 * lightingMood. One plan per shot, regenerate overwrites (no version history, same as
 * MotionGraphicPlan). Critiqued before accepting, same retry-with-feedback shape as
 * ScriptGenerationService -- regenerating a lighting plan is cheap, so one extra attempt on a weak
 * first draft costs little next to what a rookie creator actually filming a bad setup costs. */
@Service
public class LightingPlanService {

    private static final String TASK_KEY = "PRE_PROD_LIGHTING_PLAN_GENERATE";
    private static final String CRITIC_TASK_KEY = "PRE_PROD_LIGHTING_PLAN_CRITIC";
    private static final int MAX_GENERATION_ATTEMPTS = 2;

    private final ShotRepository shotRepository;
    private final LightingPlanRepository lightingPlanRepository;
    private final ShotProductReferenceRepository shotProductReferenceRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public LightingPlanService(
            ShotRepository shotRepository,
            LightingPlanRepository lightingPlanRepository,
            ShotProductReferenceRepository shotProductReferenceRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.shotRepository = shotRepository;
        this.lightingPlanRepository = lightingPlanRepository;
        this.shotProductReferenceRepository = shotProductReferenceRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public LightingPlanView generate(UUID tenantId, UUID shotId) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        String referenceAnalysis = referenceAnalysisBlock(shotId);

        LightingPlanGenerationResult parsed = null;
        String critiqueFeedback = "";
        List<String> critiqueNotesByAttempt = new ArrayList<>();
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            LlmGatewayChatResponse response = llmGatewayClient.chat(
                    tenantId.toString(),
                    "lighting-plan-" + shotId + "-attempt" + attempt,
                    new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                            JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                            Map.of(
                                    "lightingMood", shot.getLightingMood() == null ? "not specified" : shot.getLightingMood().toString(),
                                    "location", nullSafe(shot.getLocation()),
                                    "timeOfDay", shot.getTimeOfDay() == null ? "not specified" : shot.getTimeOfDay().toString(),
                                    "action", nullSafe(shot.getAction()) + critiqueFeedback,
                                    "referenceAnalysis", referenceAnalysis
                            )));

            parsed = parse(response);
            if (attempt == MAX_GENERATION_ATTEMPTS) {
                break;
            }
            PlanCritiqueResult critique = critique(tenantId, shotId, parsed);
            if (!critique.isFail()) {
                break;
            }
            String issues = String.join("; ", critique.issues() == null ? List.of() : critique.issues());
            critiqueNotesByAttempt.add("Attempt " + attempt + ": " + issues);
            critiqueFeedback = "\n\nCRITIC FEEDBACK FROM A PRIOR ATTEMPT (fix these issues, do not repeat them): " + issues;
        }
        String critiqueNotes = critiqueNotesByAttempt.isEmpty() ? null : String.join(" | ", critiqueNotesByAttempt);

        OffsetDateTime now = OffsetDateTime.now();
        LightingPlan plan = lightingPlanRepository.findByShotId(shotId).orElseGet(() -> LightingPlan.builder()
                .tenantId(tenantId)
                .shotId(shotId)
                .createdAt(now)
                .build());
        plan.setCinematicIntent(parsed.cinematicIntent());
        plan.setEstimatedSetupMinutes(parsed.estimatedSetupMinutes());
        plan.setKeyLightGear(parsed.keyLightGear());
        plan.setFillLightGear(parsed.fillLightGear());
        plan.setRimLightGear(parsed.rimLightGear());
        plan.setNegFillGear(parsed.negFillGear());
        plan.setDiffuserGear(parsed.diffuserGear());
        plan.setCameraRigGear(parsed.cameraRigGear());
        plan.setBuildSteps(parsed.buildSteps());
        plan.setSource(critiqueNotes == null ? "GENERATED" : "CRITIC");
        plan.setCritiqueNotes(critiqueNotes);
        plan.setUpdatedAt(now);
        return toView(lightingPlanRepository.save(plan));
    }

    @Transactional(readOnly = true)
    public Map<UUID, LightingPlanView> listByShotIds(UUID tenantId, List<UUID> shotIds) {
        if (shotIds == null || shotIds.isEmpty()) {
            return Map.of();
        }

        return lightingPlanRepository.findByTenantIdAndShotIdIn(tenantId, shotIds).stream()
                .collect(Collectors.toMap(
                        LightingPlan::getShotId,
                        this::toView
                ));
    }
    /** No LLM call -- applies the creator's correction directly onto the live row. Anything the
     * request omits keeps its current value rather than being blanked out. */
    @Transactional
    public LightingPlanView saveEdit(UUID tenantId, UUID shotId, SaveLightingPlanEditRequest request) {
        shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        LightingPlan plan = lightingPlanRepository.findByShotId(shotId)
                .orElseThrow(() -> PreProductionException.notFound("Shot " + shotId + " has no lighting plan yet"));

        if (request.cinematicIntent() != null) plan.setCinematicIntent(request.cinematicIntent());
        if (request.estimatedSetupMinutes() != null) plan.setEstimatedSetupMinutes(request.estimatedSetupMinutes());
        if (request.keyLightGear() != null) plan.setKeyLightGear(request.keyLightGear());
        if (request.fillLightGear() != null) plan.setFillLightGear(request.fillLightGear());
        if (request.rimLightGear() != null) plan.setRimLightGear(request.rimLightGear());
        if (request.negFillGear() != null) plan.setNegFillGear(request.negFillGear());
        if (request.diffuserGear() != null) plan.setDiffuserGear(request.diffuserGear());
        if (request.cameraRigGear() != null) plan.setCameraRigGear(request.cameraRigGear());
        if (request.buildSteps() != null) plan.setBuildSteps(request.buildSteps());
        plan.setSource("EDITED");
        plan.setCritiqueNotes(null);
        plan.setUpdatedAt(OffsetDateTime.now());
        return toView(lightingPlanRepository.save(plan));
    }

    @Transactional(readOnly = true)
    public LightingPlanView get(UUID tenantId, UUID shotId) {
        shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        return lightingPlanRepository.findByShotId(shotId)
                .map(this::toView)
                .orElseThrow(() -> PreProductionException.notFound("Shot " + shotId + " has no lighting plan yet"));
    }

    /** Folds in the shot's confirmed reference-image analysis (see ShotProductReference), when one
     * exists -- a real photographed lighting/mood reference beats the model guessing from the
     * shot's own flat fields alone. Absent for most shots today (analysis is opt-in per shot), so
     * this degrades to "nothing extra" rather than blocking generation. */
    private String referenceAnalysisBlock(UUID shotId) {
        ShotProductReference reference = shotProductReferenceRepository.findByShotId(shotId).orElse(null);
        if (reference == null) {
            return "No reference image analysis available for this shot.";
        }
        StringBuilder block = new StringBuilder("Reference image analysis for this shot:");
        appendIfPresent(block, "Dominant mood", reference.getDominantMood());
        appendIfPresent(block, "Reference lighting style", reference.getReferenceLightingStyle());
        appendIfPresent(block, "Reference camera angle", reference.getReferenceCameraAngle());
        appendIfPresent(block, "Detected subject", reference.getDetectedSubject());
        return block.toString();
    }

    private void appendIfPresent(StringBuilder block, String label, String value) {
        if (value != null && !value.isBlank()) {
            block.append("\n- ").append(label).append(": ").append(value);
        }
    }

    /** One critique call per attempt. Never blocks generation on a parse failure: an unparseable
     * critique is treated as a pass (no feedback to act on), not a hard failure. */
    private PlanCritiqueResult critique(UUID tenantId, UUID shotId, LightingPlanGenerationResult parsed) {
        try {
            LlmGatewayChatResponse response = llmGatewayClient.chat(
                    tenantId.toString(),
                    "lighting-plan-critic-" + shotId,
                    new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                            JsonExtraction.JSON_MODE_PARAMS, CRITIC_TASK_KEY,
                            Map.of(
                                    "cinematicIntent", nullSafe(parsed.cinematicIntent()),
                                    "keyLightGear", nullSafe(parsed.keyLightGear()),
                                    "fillLightGear", nullSafe(parsed.fillLightGear()),
                                    "buildSteps", nullSafe(parsed.buildSteps())
                            )));
            if (response == null || response.response() == null || response.response().isBlank()) {
                return new PlanCritiqueResult("PASS", List.of());
            }
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), PlanCritiqueResult.class);
        } catch (Exception ex) {
            return new PlanCritiqueResult("PASS", List.of());
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private LightingPlanGenerationResult parse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PreProductionException.upstream("llm-gateway returned no content for " + TASK_KEY);
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), LightingPlanGenerationResult.class);
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not parse " + TASK_KEY + " response as JSON: " + ex.getMessage());
        }
    }

    private LightingPlanView toView(LightingPlan plan) {
        return new LightingPlanView(plan.getId(), plan.getShotId(), plan.getCinematicIntent(), plan.getEstimatedSetupMinutes(),
                plan.getKeyLightGear(), plan.getFillLightGear(), plan.getRimLightGear(), plan.getNegFillGear(),
                plan.getDiffuserGear(), plan.getCameraRigGear(), plan.getBuildSteps(), plan.getSource(), plan.getCritiqueNotes());
    }
}
