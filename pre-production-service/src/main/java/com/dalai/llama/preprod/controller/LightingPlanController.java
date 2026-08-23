package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.LightingPlanView;
import com.dalai.llama.preprod.service.LightingPlanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class LightingPlanController extends BaseController {

    private final LightingPlanService lightingPlanService;

    public LightingPlanController(LightingPlanService lightingPlanService) {
        this.lightingPlanService = lightingPlanService;
    }

    @PostMapping("/v1/shots/{shotId}/lighting-plan/generate")
    public ResponseEntity<LightingPlanView> generate(@PathVariable UUID shotId) {
        return ResponseEntity.ok(lightingPlanService.generate(tenant().tenantId(), shotId));
    }

    @GetMapping("/v1/shots/{shotId}/lighting-plan")
    public ResponseEntity<LightingPlanView> get(@PathVariable UUID shotId) {
        return ResponseEntity.ok(lightingPlanService.get(tenant().tenantId(), shotId));
    }
}
