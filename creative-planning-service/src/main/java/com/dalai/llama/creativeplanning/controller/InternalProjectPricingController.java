package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.ProjectQuoteView;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** billing-service's per-project spend cap and the client's lock payment both need the price a
 * pre-production-service project was actually sold at -- see {@link
 * ProjectRequirementService#findProjectQuote}'s javadoc for why this can't be resolved from a
 * direct FK. Same internal, no-JWT convention as {@link InternalMarketingPlanController} -- {@code
 * /api/v1/internal/**} is permitAll, tenantId comes from the path. 404 when the project never went
 * through the quoted-requirement flow. */
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/quoted-price")
public class InternalProjectPricingController {

    private final ProjectRequirementService projectRequirementService;

    public InternalProjectPricingController(ProjectRequirementService projectRequirementService) {
        this.projectRequirementService = projectRequirementService;
    }

    @GetMapping
    public ResponseEntity<ProjectQuoteView> get(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return ResponseEntity.of(projectRequirementService.findProjectQuote(tenantId, projectId));
    }
}
