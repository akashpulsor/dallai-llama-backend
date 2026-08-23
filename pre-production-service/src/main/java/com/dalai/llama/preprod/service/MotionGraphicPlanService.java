package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.domain.entity.MotionGraphicPlan;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.dto.MotionGraphicPlanView;
import com.dalai.llama.preprod.repository.MotionGraphicPlanRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import com.dalai.llama.preprod.service.generation.JsonExtraction;
import com.dalai.llama.preprod.service.generation.MotionGraphicPlanGenerationResult;
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

/** Plans (never renders -- no motion-graphics compositor exists in this backend) what a
 * MOTION_GRAPHIC shot should contain: the concept, on-screen text, visual style, and animation
 * notes a human designer/editor executes from. One plan per shot, regenerate overwrites. */
@Service
public class MotionGraphicPlanService {

    private static final String TASK_KEY = "PRE_PROD_MOTION_GRAPHIC_PLAN_GENERATE";

    private final ShotRepository shotRepository;
    private final MotionGraphicPlanRepository motionGraphicPlanRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public MotionGraphicPlanService(
            ShotRepository shotRepository,
            MotionGraphicPlanRepository motionGraphicPlanRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.shotRepository = shotRepository;
        this.motionGraphicPlanRepository = motionGraphicPlanRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public MotionGraphicPlanView generate(UUID tenantId, UUID shotId) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        if (shot.getShotType() != ShotType.MOTION_GRAPHIC) {
            throw PreProductionException.badRequest("Shot " + shotId + " is not a MOTION_GRAPHIC shot");
        }

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "motion-graphic-plan-" + shotId,
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of(
                                "action", nullSafe(shot.getAction()),
                                "scriptLine", nullSafe(shot.getScriptLine()),
                                "textOverlay", nullSafe(shot.getTextOverlay()),
                                "durationSeconds", String.valueOf(shot.getDurationSeconds() == null ? 4 : shot.getDurationSeconds())
                        )));

        MotionGraphicPlanGenerationResult parsed = parse(response);

        OffsetDateTime now = OffsetDateTime.now();
        MotionGraphicPlan plan = motionGraphicPlanRepository.findByShotId(shotId).orElseGet(() -> MotionGraphicPlan.builder()
                .tenantId(tenantId)
                .shotId(shotId)
                .createdAt(now)
                .build());
        plan.setConcept(parsed.concept());
        plan.setOnScreenText(parsed.onScreenText());
        plan.setVisualStyle(parsed.visualStyle());
        plan.setAnimationNotes(parsed.animationNotes());
        plan.setDurationSeconds(parsed.durationSeconds());
        plan.setUpdatedAt(now);
        return toView(motionGraphicPlanRepository.save(plan));
    }

    @Transactional(readOnly = true)
    public MotionGraphicPlanView get(UUID tenantId, UUID shotId) {
        shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        return motionGraphicPlanRepository.findByShotId(shotId)
                .map(this::toView)
                .orElseThrow(() -> PreProductionException.notFound("Shot " + shotId + " has no motion graphic plan yet"));
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private MotionGraphicPlanGenerationResult parse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PreProductionException.upstream("llm-gateway returned no content for " + TASK_KEY);
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), MotionGraphicPlanGenerationResult.class);
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not parse " + TASK_KEY + " response as JSON: " + ex.getMessage());
        }
    }

    private MotionGraphicPlanView toView(MotionGraphicPlan plan) {
        return new MotionGraphicPlanView(plan.getId(), plan.getShotId(), plan.getConcept(), plan.getOnScreenText(),
                plan.getVisualStyle(), plan.getAnimationNotes(), plan.getDurationSeconds());
    }
}
