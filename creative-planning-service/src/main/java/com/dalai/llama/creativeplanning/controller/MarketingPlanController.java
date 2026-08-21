package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.BrandPlanExportView;
import com.dalai.llama.creativeplanning.dto.GenerateMarketingPlanRequest;
import com.dalai.llama.creativeplanning.dto.MarketingPlanGenerationResultView;
import com.dalai.llama.creativeplanning.dto.MarketingPlanMessageView;
import com.dalai.llama.creativeplanning.dto.MarketingPlanView;
import com.dalai.llama.creativeplanning.dto.ReviseMarketingPlanRequest;
import com.dalai.llama.creativeplanning.dto.SendMessageRequest;
import com.dalai.llama.creativeplanning.service.export.MarketingPlanExportService;
import com.dalai.llama.creativeplanning.service.marketingplan.MarketingPlanChatService;
import com.dalai.llama.creativeplanning.service.marketingplan.MarketingPlanGenerationService;
import com.dalai.llama.creativeplanning.service.marketingplan.MarketingPlanRevisionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class MarketingPlanController extends BaseController {

    private final MarketingPlanGenerationService marketingPlanGenerationService;
    private final MarketingPlanChatService marketingPlanChatService;
    private final MarketingPlanRevisionService marketingPlanRevisionService;
    private final MarketingPlanExportService marketingPlanExportService;

    public MarketingPlanController(
            MarketingPlanGenerationService marketingPlanGenerationService,
            MarketingPlanChatService marketingPlanChatService,
            MarketingPlanRevisionService marketingPlanRevisionService,
            MarketingPlanExportService marketingPlanExportService
    ) {
        this.marketingPlanGenerationService = marketingPlanGenerationService;
        this.marketingPlanChatService = marketingPlanChatService;
        this.marketingPlanRevisionService = marketingPlanRevisionService;
        this.marketingPlanExportService = marketingPlanExportService;
    }

    @PostMapping("/v1/marketing-plans")
    public ResponseEntity<MarketingPlanGenerationResultView> generate(@Valid @RequestBody GenerateMarketingPlanRequest request) {
        return ResponseEntity.ok(marketingPlanGenerationService.generate(tenant().tenantId(), request));
    }

    @GetMapping("/v1/marketing-plans/{planId}")
    public ResponseEntity<MarketingPlanView> get(@PathVariable UUID planId) {
        return ResponseEntity.ok(marketingPlanGenerationService.get(tenant().tenantId(), planId));
    }

    @GetMapping("/v1/marketing-plans")
    public ResponseEntity<List<MarketingPlanView>> list() {
        return ResponseEntity.ok(marketingPlanGenerationService.list(tenant().tenantId()));
    }

    @PostMapping("/v1/marketing-plans/{planId}/messages")
    public ResponseEntity<MarketingPlanMessageView> sendMessage(@PathVariable UUID planId, @Valid @RequestBody SendMessageRequest request) {
        return ResponseEntity.ok(marketingPlanChatService.sendMessage(tenant().tenantId(), planId, request));
    }

    @GetMapping("/v1/marketing-plans/{planId}/messages")
    public ResponseEntity<List<MarketingPlanMessageView>> messages(@PathVariable UUID planId) {
        return ResponseEntity.ok(marketingPlanChatService.history(tenant().tenantId(), planId));
    }

    @PostMapping("/v1/marketing-plans/{planId}/revise")
    public ResponseEntity<MarketingPlanGenerationResultView> revise(@PathVariable UUID planId, @Valid @RequestBody ReviseMarketingPlanRequest request) {
        return ResponseEntity.ok(marketingPlanRevisionService.revise(tenant().tenantId(), planId, request));
    }

    @PostMapping("/v1/marketing-plans/{planId}/export-pdf")
    public ResponseEntity<BrandPlanExportView> exportPdf(@PathVariable UUID planId) {
        return ResponseEntity.ok(marketingPlanExportService.exportPdf(tenant().tenantId(), planId));
    }
}
