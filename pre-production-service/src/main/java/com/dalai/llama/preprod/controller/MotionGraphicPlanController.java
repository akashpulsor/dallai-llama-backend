package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.MotionGraphicPlanView;
import com.dalai.llama.preprod.service.MotionGraphicPlanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class MotionGraphicPlanController extends BaseController {

    private final MotionGraphicPlanService motionGraphicPlanService;

    public MotionGraphicPlanController(MotionGraphicPlanService motionGraphicPlanService) {
        this.motionGraphicPlanService = motionGraphicPlanService;
    }

    @PostMapping("/v1/shots/{shotId}/motion-graphic-plan/generate")
    public ResponseEntity<MotionGraphicPlanView> generate(@PathVariable UUID shotId) {
        return ResponseEntity.ok(motionGraphicPlanService.generate(tenant().tenantId(), shotId));
    }

    @GetMapping("/v1/shots/{shotId}/motion-graphic-plan")
    public ResponseEntity<MotionGraphicPlanView> get(@PathVariable UUID shotId) {
        return ResponseEntity.ok(motionGraphicPlanService.get(tenant().tenantId(), shotId));
    }
}
