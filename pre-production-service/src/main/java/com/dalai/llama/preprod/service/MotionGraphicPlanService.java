package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.service.creativedirection.CreativeDirectionContextService;
import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.domain.entity.MotionGraphicPlan;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.dto.MotionGraphicPlanRequests;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Plans what a MOTION_GRAPHIC shot should contain: the concept, on-screen text, visual style and
 * animation notes. The video studio builds the shot from it -- each animation step becomes an action
 * on the timeline and the on-screen text one that must appear -- so the creator can edit it, ask for
 * it to be enhanced, or plan it afresh. One plan per shot. */
@Service
public class MotionGraphicPlanService {

    private static final String TASK_KEY = "PRE_PROD_MOTION_GRAPHIC_PLAN_GENERATE";
    private static final String ENHANCE_TASK_KEY = "PRE_PROD_MOTION_GRAPHIC_PLAN_ENHANCE";

    private final ShotRepository shotRepository;
    private final MotionGraphicPlanRepository motionGraphicPlanRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;
    private final CreativeDirectionContextService creativeDirectionContextService;

    public MotionGraphicPlanService(
            ShotRepository shotRepository,
            MotionGraphicPlanRepository motionGraphicPlanRepository,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${pre-production.llm-gateway.default-text-model}") String defaultModel,
            CreativeDirectionContextService creativeDirectionContextService
    ) {
        this.creativeDirectionContextService = creativeDirectionContextService;
        this.shotRepository = shotRepository;
        this.motionGraphicPlanRepository = motionGraphicPlanRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    /** REQUIRES_NEW rather than the usual REQUIRED: {@link ShotListGenerationService} calls this
     * once per MOTION_GRAPHIC shot right after saving the shot list, in the same ambient
     * transaction as the shots themselves. Without its own transaction, an LLM hiccup planning
     * one shot's graphic would mark that shared transaction rollback-only and take the entire
     * (otherwise successful) shot list down with it -- a plan is best-effort polish on top of the
     * shot list, never a reason to lose it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MotionGraphicPlanView generate(UUID tenantId, UUID shotId) {
        Shot shot = motionGraphicShot(tenantId, shotId);
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "motion-graphic-plan-" + shotId,
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of(
                                "action", nullSafe(shot.getAction()),
                                "scriptLine", nullSafe(shot.getScriptLine()),
                                "textOverlay", nullSafe(shot.getTextOverlay()),
                                "durationSeconds", String.valueOf(shot.getDurationSeconds() == null ? 4 : shot.getDurationSeconds()),
                                "creativeDirection", creativeDirectionContextService.promptBlock(tenantId, shot.getProjectId())
                        )).withProjectId(shot.getProjectId()));

        MotionGraphicPlanGenerationResult parsed = parse(response, TASK_KEY);

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

    /** Saves the plan as the creator wrote it. Creates one if the shot had none. */
    @Transactional
    public MotionGraphicPlanView update(UUID tenantId, UUID shotId, MotionGraphicPlanRequests.Edit edit) {
        Shot shot = motionGraphicShot(tenantId, shotId);
        OffsetDateTime now = OffsetDateTime.now();
        MotionGraphicPlan plan = motionGraphicPlanRepository.findByShotId(shotId).orElseGet(() -> MotionGraphicPlan.builder()
                .tenantId(tenantId)
                .shotId(shotId)
                .durationSeconds(shot.getDurationSeconds())
                .createdAt(now)
                .build());
        plan.setConcept(blankToNull(edit.concept()));
        plan.setOnScreenText(blankToNull(edit.onScreenText()));
        plan.setVisualStyle(blankToNull(edit.visualStyle()));
        plan.setAnimationNotes(blankToNull(edit.animationNotes()));
        plan.setUpdatedAt(now);
        return toView(motionGraphicPlanRepository.save(plan));
    }

    /**
     * The plan improved the way the creator asked, as a suggestion -- nothing is saved. They read it
     * beside their own and keep it with {@link #update}, the same way a rephrased line is offered.
     */
    @Transactional(readOnly = true)
    public MotionGraphicPlanView enhance(UUID tenantId, UUID shotId, String instruction) {
        Shot shot = motionGraphicShot(tenantId, shotId);
        MotionGraphicPlan current = motionGraphicPlanRepository.findByShotId(shotId).orElse(null);
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("action", nullSafe(shot.getAction()));
        variables.put("scriptLine", nullSafe(shot.getScriptLine()));
        variables.put("durationSeconds", String.valueOf(shot.getDurationSeconds() == null ? 4 : shot.getDurationSeconds()));
        variables.put("creativeDirection", creativeDirectionContextService.promptBlock(tenantId, shot.getProjectId()));
        variables.put("concept", current == null ? "" : nullSafe(current.getConcept()));
        variables.put("onScreenText", current == null ? "" : nullSafe(current.getOnScreenText()));
        variables.put("visualStyle", current == null ? "" : nullSafe(current.getVisualStyle()));
        variables.put("animationNotes", current == null ? "" : nullSafe(current.getAnimationNotes()));
        variables.put("instruction", instruction.strip());
        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "motion-graphic-enhance-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, ENHANCE_TASK_KEY, variables).withProjectId(shot.getProjectId()));
        MotionGraphicPlanGenerationResult parsed = parse(response, ENHANCE_TASK_KEY);
        return new MotionGraphicPlanView(current == null ? null : current.getId(), shotId, parsed.concept(),
                parsed.onScreenText(), parsed.visualStyle(), parsed.animationNotes(),
                current == null ? shot.getDurationSeconds() : current.getDurationSeconds());
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

    private Shot motionGraphicShot(UUID tenantId, UUID shotId) {
        Shot shot = shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
        if (shot.getShotType() != ShotType.MOTION_GRAPHIC) {
            throw PreProductionException.badRequest("Shot " + shotId + " is not a MOTION_GRAPHIC shot");
        }
        return shot;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private MotionGraphicPlanGenerationResult parse(LlmGatewayChatResponse response, String taskKey) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw PreProductionException.upstream("llm-gateway returned no content for " + taskKey);
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), MotionGraphicPlanGenerationResult.class);
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not parse " + taskKey + " response as JSON: " + ex.getMessage());
        }
    }

    private MotionGraphicPlanView toView(MotionGraphicPlan plan) {
        return new MotionGraphicPlanView(plan.getId(), plan.getShotId(), plan.getConcept(), plan.getOnScreenText(),
                plan.getVisualStyle(), plan.getAnimationNotes(), plan.getDurationSeconds());
    }
}
