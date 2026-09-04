package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/** billing-service's per-project spend cap needs the price a pre-production-service project was
 * actually sold at -- see {@link ProjectRequirementService#findQuotedTotalPrice}'s javadoc for
 * why this can't be resolved from a direct FK. Same internal, no-JWT convention as {@link
 * InternalMarketingPlanController} -- {@code /api/v1/internal/**} is permitAll, tenantId comes
 * from the path. */
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/quoted-price")
public class InternalProjectPricingController {

    private final ProjectRequirementService projectRequirementService;

    public InternalProjectPricingController(ProjectRequirementService projectRequirementService) {
        this.projectRequirementService = projectRequirementService;
    }

    @GetMapping
    public QuotedPriceView get(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        BigDecimal totalPrice = projectRequirementService.findQuotedTotalPrice(tenantId, projectId);
        return new QuotedPriceView(totalPrice);
    }

    public record QuotedPriceView(BigDecimal quotedTotalPrice) {}
}
