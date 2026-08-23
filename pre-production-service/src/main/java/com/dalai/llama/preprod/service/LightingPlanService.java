package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.LightingPlan;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.dto.LightingPlanView;
import com.dalai.llama.preprod.repository.LightingPlanRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.generation.LightingPlanGenerationResult;
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

/** Plans a shot's lighting build sheet -- feeds the LIGHTING {@code ShotImageKind}'s prompt with
 * real structure (6 gear slots, ordered setup steps) instead of just the shot's flat
 * lightingMood. One plan per shot, regenerate overwrites. */
@Service
public class LightingPlanService {

    private static final String TASK_KEY = "PRE_PROD_LIGHTING_PLAN_GENERATE";

    private final ShotRepository shotRepository;
    private final LightingPlanRepository lightingPlanRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public LightingPlanService(
            ShotRepository shotRepository,
            LightingPlanRepository lightingPlanRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.shotRepository = shotRepository;
        this.lightingPlanRepository = lightingPlanRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public LightingPlanView generate(UUID tenantId, UUID shotId) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "lighting-plan-" + shotId,
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of(
                                "lightingMood", shot.getLightingMood() == null ? "not specified" : shot.getLightingMood().toString(),
                                "location", nullSafe(shot.getLocation()),
                                "timeOfDay", shot.getTimeOfDay() == null ? "not specified" : shot.getTimeOfDay().toString(),
                                "action", nullSafe(shot.getAction())
                        )));

        LightingPlanGenerationResult parsed = parse(response);

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
        plan.setUpdatedAt(now);
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
                plan.getDiffuserGear(), plan.getCameraRigGear(), plan.getBuildSteps());
    }
}
