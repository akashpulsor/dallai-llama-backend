package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.BrandPlanExportView;
import com.dalai.llama.creativeplanning.dto.MarketingPlanGenerationResultView;
import com.dalai.llama.creativeplanning.dto.ReviseMarketingPlanRequest;
import com.dalai.llama.creativeplanning.service.export.MarketingPlanExportService;
import com.dalai.llama.creativeplanning.service.marketingplan.MarketingPlanRevisionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** chat-service's action executors (export/revise a marketing plan) are the real caller here --
 * {@code /api/v1/internal/**} is permitAll (see {@code SecurityConfig}), tenantId comes from the
 * path. Same {@code MarketingPlanExportService}/{@code MarketingPlanRevisionService} the
 * authenticated {@code MarketingPlanController} uses, just reachable without a JWT the caller
 * never actually had. */
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}/marketing-plans/{planId}")
public class InternalMarketingPlanController {

    private final MarketingPlanExportService marketingPlanExportService;
    private final MarketingPlanRevisionService marketingPlanRevisionService;

    public InternalMarketingPlanController(MarketingPlanExportService marketingPlanExportService, MarketingPlanRevisionService marketingPlanRevisionService) {
        this.marketingPlanExportService = marketingPlanExportService;
        this.marketingPlanRevisionService = marketingPlanRevisionService;
    }

    @PostMapping("/export-pdf")
    public BrandPlanExportView exportPdf(@PathVariable UUID tenantId, @PathVariable UUID planId) {
        return marketingPlanExportService.exportPdf(tenantId, planId);
    }

    @PostMapping("/revise")
    public MarketingPlanGenerationResultView revise(@PathVariable UUID tenantId, @PathVariable UUID planId, @Valid @RequestBody ReviseMarketingPlanRequest request) {
        return marketingPlanRevisionService.revise(tenantId, planId, request);
    }
}
