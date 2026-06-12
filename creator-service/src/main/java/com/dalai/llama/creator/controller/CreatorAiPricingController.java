package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.service.CreatorAiPricingService;
import com.dalai.llama.creator.service.ProviderCreditHealthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/creator/ai-pricing")
public class CreatorAiPricingController {

    private final CreatorAiPricingService pricingService;
    private final ProviderCreditHealthService providerCreditHealthService;

    public CreatorAiPricingController(
            CreatorAiPricingService pricingService,
            ProviderCreditHealthService providerCreditHealthService
    ) {
        this.pricingService = pricingService;
        this.providerCreditHealthService = providerCreditHealthService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getPricingMatrix() {
        return ResponseEntity.ok(pricingService.pricingMatrix());
    }

    @GetMapping("/provider-credits")
    public ResponseEntity<Map<String, Object>> getProviderCredits(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId
    ) {
        return ResponseEntity.ok(providerCreditHealthService.creditHealth(tenantId));
    }
}
