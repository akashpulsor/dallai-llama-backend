package com.dalai.llama.critic.controller;

import com.dalai.llama.critic.dto.CritiqueRequest;
import com.dalai.llama.critic.dto.CritiqueResult;
import com.dalai.llama.critic.dto.idea.IdeaCritiqueRequest;
import com.dalai.llama.critic.dto.idea.IdeaCritiqueResult;
import com.dalai.llama.critic.dto.marketingplan.MarketingPlanCritiqueRequest;
import com.dalai.llama.critic.dto.marketingplan.MarketingPlanCritiqueResult;
import com.dalai.llama.critic.service.critique.CritiqueOrchestrator;
import com.dalai.llama.critic.service.ideacritique.IdeaCritiqueService;
import com.dalai.llama.critic.service.marketingplancritique.MarketingPlanCritiqueOrchestrator;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** critic-service's real callers (pre-production-service, creative-planning-service) are
 * other services, not browsers -- {@code /api/v1/internal/**} is permitAll (see {@code
 * SecurityConfig}), tenantId comes from the path. The mandatory-gate discipline is unchanged:
 * this is the same {@code CritiqueOrchestrator}/{@code MarketingPlanCritiqueOrchestrator}/{@code
 * IdeaCritiqueService}, just reached without a JWT the calling service never actually had to
 * forward. */
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}")
public class InternalCritiqueController {

    private final CritiqueOrchestrator critiqueOrchestrator;
    private final MarketingPlanCritiqueOrchestrator marketingPlanCritiqueOrchestrator;
    private final IdeaCritiqueService ideaCritiqueService;

    public InternalCritiqueController(
            CritiqueOrchestrator critiqueOrchestrator,
            MarketingPlanCritiqueOrchestrator marketingPlanCritiqueOrchestrator,
            IdeaCritiqueService ideaCritiqueService
    ) {
        this.critiqueOrchestrator = critiqueOrchestrator;
        this.marketingPlanCritiqueOrchestrator = marketingPlanCritiqueOrchestrator;
        this.ideaCritiqueService = ideaCritiqueService;
    }

    @PostMapping("/critiques")
    public CritiqueResult critique(@PathVariable UUID tenantId, @Valid @RequestBody CritiqueRequest request) {
        return critiqueOrchestrator.critique(tenantId, request);
    }

    @PostMapping("/marketing-plan-critiques")
    public MarketingPlanCritiqueResult marketingPlanCritique(@PathVariable UUID tenantId, @Valid @RequestBody MarketingPlanCritiqueRequest request) {
        return marketingPlanCritiqueOrchestrator.critique(tenantId, request);
    }

    @PostMapping("/idea-critiques")
    public IdeaCritiqueResult ideaCritique(@PathVariable UUID tenantId, @Valid @RequestBody IdeaCritiqueRequest request) {
        return ideaCritiqueService.critique(tenantId, request);
    }
}
