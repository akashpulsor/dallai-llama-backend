package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.MotionGraphicPlanView;
import com.dalai.llama.preprod.service.MotionGraphicPlanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import jakarta.validation.Valid;
import com.dalai.llama.preprod.dto.MotionGraphicPlanRequests;
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

    /** The plan as the creator wrote it. */
    @PutMapping("/v1/shots/{shotId}/motion-graphic-plan")
    public ResponseEntity<MotionGraphicPlanView> update(@PathVariable UUID shotId,
                                                        @RequestBody MotionGraphicPlanRequests.Edit edit) {
        return ResponseEntity.ok(motionGraphicPlanService.update(tenant().tenantId(), shotId, edit));
    }

    /** A suggested improvement, not saved -- the creator keeps it with PUT. */
    @PostMapping("/v1/shots/{shotId}/motion-graphic-plan/enhance")
    public ResponseEntity<MotionGraphicPlanView> enhance(@PathVariable UUID shotId,
                                                         @Valid @RequestBody MotionGraphicPlanRequests.Enhance request) {
        return ResponseEntity.ok(motionGraphicPlanService.enhance(tenant().tenantId(), shotId, request.instruction()));
    }

    @GetMapping("/v1/shots/{shotId}/motion-graphic-plan")
    public ResponseEntity<MotionGraphicPlanView> get(@PathVariable UUID shotId) {
        return ResponseEntity.ok(motionGraphicPlanService.get(tenant().tenantId(), shotId));
    }
}
