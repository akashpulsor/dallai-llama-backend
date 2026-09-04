package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.CameraPlan;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotProductReference;
import com.dalai.llama.preprod.dto.CameraPlanView;
import com.dalai.llama.preprod.dto.SaveCameraPlanEditRequest;
import com.dalai.llama.preprod.repository.CameraPlanRepository;
import com.dalai.llama.preprod.repository.ShotProductReferenceRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.generation.CameraPlanGenerationResult;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
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

/** Plans a shot's camera/blocking sheet -- feeds the CAMERA_PLAN {@code ShotImageKind}'s prompt
 * with real structure (blocking map, ordered execution steps, gimbal settings) instead of just
 * the shot's flat camera fields. One plan per shot, regenerate overwrites (no version history,
 * same as MotionGraphicPlan). Critiqued before accepting, same retry-with-feedback shape as
 * ScriptGenerationService -- a missed safety flag or an unexecutable blocking map is worth one
 * extra LLM call to catch before a creator reads it as the shoot plan. */
@Service
public class CameraPlanService {

    private static final String TASK_KEY = "PRE_PROD_CAMERA_PLAN_GENERATE";
    private static final String CRITIC_TASK_KEY = "PRE_PROD_CAMERA_PLAN_CRITIC";
    private static final int MAX_GENERATION_ATTEMPTS = 2;

    private final ShotRepository shotRepository;
    private final CameraPlanRepository cameraPlanRepository;
    private final ShotProductReferenceRepository shotProductReferenceRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public CameraPlanService(
            ShotRepository shotRepository,
            CameraPlanRepository cameraPlanRepository,
            ShotProductReferenceRepository shotProductReferenceRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.shotRepository = shotRepository;
        this.cameraPlanRepository = cameraPlanRepository;
        this.shotProductReferenceRepository = shotProductReferenceRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public CameraPlanView generate(UUID tenantId, UUID shotId) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        String referenceAnalysis = referenceAnalysisBlock(shotId);

        CameraPlanGenerationResult parsed = null;
        String critiqueFeedback = "";
        List<String> critiqueNotesByAttempt = new ArrayList<>();
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            LlmGatewayChatResponse response = llmGatewayClient.chat(
                    tenantId.toString(),
                    "camera-plan-" + shotId + "-attempt" + attempt,
                    new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                            JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                            Map.of(
                                    "cameraAngle", nullSafe(shot.getCameraAngle()),
                                    "cameraMovement", nullSafe(shot.getCameraMovement()),
                                    "cameraShotSize", shot.getCameraShotSize() == null ? "not specified" : shot.getCameraShotSize().toString(),
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
        CameraPlan plan = cameraPlanRepository.findByShotId(shotId).orElseGet(() -> CameraPlan.builder()
                .tenantId(tenantId)
                .shotId(shotId)
                .createdAt(now)
                .build());
        plan.setBlockingMap(parsed.blockingMap());
        plan.setExecutionSteps(parsed.executionSteps());
        plan.setGimbalEnabled(Boolean.TRUE.equals(parsed.gimbalEnabled()));
        plan.setGimbalDevice(parsed.gimbalDevice());
        plan.setGimbalMode(parsed.gimbalMode());
        plan.setGimbalPanSpeed(parsed.gimbalPanSpeed());
        plan.setGimbalTiltSpeed(parsed.gimbalTiltSpeed());
        plan.setSafetyFlags(parsed.safetyFlags());
        plan.setRequiresCoordinator(Boolean.TRUE.equals(parsed.requiresCoordinator()));
        plan.setComplianceNote(parsed.complianceNote());
        plan.setSource(critiqueNotes == null ? "GENERATED" : "CRITIC");
        plan.setCritiqueNotes(critiqueNotes);
        plan.setUpdatedAt(now);
        return toView(cameraPlanRepository.save(plan));
    }

    /** No LLM call -- applies the creator's correction directly onto the live row. Anything the
     * request omits keeps its current value rather than being blanked out. */
    @Transactional
    public CameraPlanView saveEdit(UUID tenantId, UUID shotId, SaveCameraPlanEditRequest request) {
        shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        CameraPlan plan = cameraPlanRepository.findByShotId(shotId)
                .orElseThrow(() -> PreProductionException.notFound("Shot " + shotId + " has no camera plan yet"));

        if (request.blockingMap() != null) plan.setBlockingMap(request.blockingMap());
        if (request.executionSteps() != null) plan.setExecutionSteps(request.executionSteps());
        if (request.gimbalEnabled() != null) plan.setGimbalEnabled(request.gimbalEnabled());
        if (request.gimbalDevice() != null) plan.setGimbalDevice(request.gimbalDevice());
        if (request.gimbalMode() != null) plan.setGimbalMode(request.gimbalMode());
        if (request.gimbalPanSpeed() != null) plan.setGimbalPanSpeed(request.gimbalPanSpeed());
        if (request.gimbalTiltSpeed() != null) plan.setGimbalTiltSpeed(request.gimbalTiltSpeed());
        if (request.safetyFlags() != null) plan.setSafetyFlags(request.safetyFlags());
        if (request.requiresCoordinator() != null) plan.setRequiresCoordinator(request.requiresCoordinator());
        if (request.complianceNote() != null) plan.setComplianceNote(request.complianceNote());
        plan.setSource("EDITED");
        plan.setCritiqueNotes(null);
        plan.setUpdatedAt(OffsetDateTime.now());
        return toView(cameraPlanRepository.save(plan));
    }

    @Transactional(readOnly = true)
    public CameraPlanView get(UUID tenantId, UUID shotId) {
        shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        return cameraPlanRepository.findByShotId(shotId)
                .map(this::toView)
                .orElseThrow(() -> PreProductionException.notFound("Shot " + shotId + " has no camera plan yet"));
    }

    /** Folds in the shot's confirmed reference-image analysis (see ShotProductReference), when one
     * exists -- a real photographed camera-angle/motion reference beats the model guessing from
     * the shot's own flat fields alone. Absent for most shots today (analysis is opt-in per shot),
     * so this degrades to "nothing extra" rather than blocking generation. */
    private String referenceAnalysisBlock(UUID shotId) {
        ShotProductReference reference = shotProductReferenceRepository.findByShotId(shotId).orElse(null);
        if (reference == null) {
            return "No reference image analysis available for this shot.";
        }
        StringBuilder block = new StringBuilder("Reference image analysis for this shot:");
        appendIfPresent(block, "Reference camera angle", reference.getReferenceCameraAngle());
        appendIfPresent(block, "Reference motion", reference.getReferenceMotion());
        appendIfPresent(block, "Dominant mood", reference.getDominantMood());
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
    private PlanCritiqueResult critique(UUID tenantId, UUID shotId, CameraPlanGenerationResult parsed) {
        try {
            LlmGatewayChatResponse response = llmGatewayClient.chat(
                    tenantId.toString(),
                    "camera-plan-critic-" + shotId,
                    new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                            JsonExtraction.JSON_MODE_PARAMS, CRITIC_TASK_KEY,
                            Map.of(
                                    "blockingMap", nullSafe(parsed.blockingMap()),
                                    "executionSteps", nullSafe(parsed.executionSteps()),
                                    "gimbalEnabled", String.valueOf(Boolean.TRUE.equals(parsed.gimbalEnabled())),
                                    "safetyFlags", nullSafe(parsed.safetyFlags())
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

    private CameraPlanGenerationResult parse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PreProductionException.upstream("llm-gateway returned no content for " + TASK_KEY);
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), CameraPlanGenerationResult.class);
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not parse " + TASK_KEY + " response as JSON: " + ex.getMessage());
        }
    }

    private CameraPlanView toView(CameraPlan plan) {
        return new CameraPlanView(plan.getId(), plan.getShotId(), plan.getBlockingMap(), plan.getExecutionSteps(),
                plan.getGimbalEnabled(), plan.getGimbalDevice(), plan.getGimbalMode(), plan.getGimbalPanSpeed(),
                plan.getGimbalTiltSpeed(), plan.getSafetyFlags(), plan.getRequiresCoordinator(), plan.getComplianceNote(),
                plan.getSource(), plan.getCritiqueNotes());
    }
}
