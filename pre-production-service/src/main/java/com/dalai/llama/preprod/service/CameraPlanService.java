package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.CameraPlan;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.dto.CameraPlanView;
import com.dalai.llama.preprod.repository.CameraPlanRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.generation.CameraPlanGenerationResult;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Plans a shot's camera/blocking sheet -- feeds the CAMERA_PLAN {@code ShotImageKind}'s prompt
 * with real structure (blocking map, ordered execution steps, gimbal settings) instead of just
 * the shot's flat camera fields. One plan per shot, regenerate overwrites. */
@Service
public class CameraPlanService {

    private static final String TASK_KEY = "PRE_PROD_CAMERA_PLAN_GENERATE";

    private final ShotRepository shotRepository;
    private final CameraPlanRepository cameraPlanRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public CameraPlanService(
            ShotRepository shotRepository,
            CameraPlanRepository cameraPlanRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.shotRepository = shotRepository;
        this.cameraPlanRepository = cameraPlanRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public CameraPlanView generate(UUID tenantId, UUID shotId) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "camera-plan-" + shotId,
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of(
                                "cameraAngle", nullSafe(shot.getCameraAngle()),
                                "cameraMovement", nullSafe(shot.getCameraMovement()),
                                "cameraShotSize", shot.getCameraShotSize() == null ? "not specified" : shot.getCameraShotSize().toString(),
                                "action", nullSafe(shot.getAction())
                        )));

        CameraPlanGenerationResult parsed = parse(response);

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
        plan.setUpdatedAt(now);
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
                plan.getGimbalTiltSpeed(), plan.getSafetyFlags(), plan.getRequiresCoordinator(), plan.getComplianceNote());
    }
}
