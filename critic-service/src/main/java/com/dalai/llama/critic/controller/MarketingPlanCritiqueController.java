package com.dalai.llama.critic.controller;

import com.dalai.llama.critic.dto.marketingplan.MarketingPlanCritiqueRequest;
import com.dalai.llama.critic.dto.marketingplan.MarketingPlanCritiqueResult;
import com.dalai.llama.critic.service.marketingplancritique.MarketingPlanCritiqueOrchestrator;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MarketingPlanCritiqueController extends BaseController {

    private final MarketingPlanCritiqueOrchestrator orchestrator;

    public MarketingPlanCritiqueController(MarketingPlanCritiqueOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/v1/marketing-plan-critiques")
    public ResponseEntity<MarketingPlanCritiqueResult> critique(@Valid @RequestBody MarketingPlanCritiqueRequest request) {
        return ResponseEntity.ok(orchestrator.critique(tenant().tenantId(), request));
    }
}
