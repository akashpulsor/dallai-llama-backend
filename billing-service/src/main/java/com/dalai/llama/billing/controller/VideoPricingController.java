package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.service.VideoPricingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Tenant-facing live estimate -- lets creator-ui show a running price (platform rate x seconds,
 * plus the creator's own configured margin) as someone types a duration into the Start New Idea
 * modal, before they submit. Same {@code /api/v1/billing/{tenantId}/...} convention as {@link
 * WalletController}. */
@RestController
@RequestMapping("/api/v1/billing/{tenantId}/video-pricing")
@RequiredArgsConstructor
@Validated
@Tag(name = "Video Pricing", description = "Per-second video pricing estimate")
public class VideoPricingController {

    private final VideoPricingService videoPricingService;
    private final com.dalai.llama.billing.service.ProductionCostEstimateService productionCostEstimateService;

    /** Tentative production cost as a range, priced at the models' real rates -- shown to the
     * creator on the new brief form; the client never sees it. */
    @GetMapping("/estimate")
    @Operation(summary = "Tentative production cost range for a given video duration")
    public ResponseEntity<com.dalai.llama.billing.service.ProductionCostEstimateService.Estimate> estimate(
            @PathVariable UUID tenantId, @RequestParam @Positive int durationSeconds) {
        return ResponseEntity.ok(productionCostEstimateService.estimate(durationSeconds));
    }

    @GetMapping("/quote")
    @Operation(summary = "Estimate the price for a given video duration")
    public ResponseEntity<VideoPricingService.Quote> quote(
            @PathVariable UUID tenantId, @RequestParam @Positive int durationSeconds) {
        return ResponseEntity.ok(videoPricingService.quote(tenantId, durationSeconds));
    }
}
