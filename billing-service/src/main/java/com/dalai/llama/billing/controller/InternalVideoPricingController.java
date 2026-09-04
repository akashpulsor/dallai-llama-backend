package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.service.VideoPricingService;
import io.swagger.v3.oas.annotations.Hidden;
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

/** Called by creative-planning-service when it creates a standalone project requirement, to
 * snapshot the price (platform base + this creator's own margin) onto that requirement at
 * creation time. Same internal, no-JWT convention as {@link InternalBillingController}. */
@Validated
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}/video-pricing")
@RequiredArgsConstructor
@Hidden
public class InternalVideoPricingController {

    private final VideoPricingService videoPricingService;

    @GetMapping("/quote")
    public ResponseEntity<VideoPricingService.Quote> quote(
            @PathVariable UUID tenantId, @RequestParam @Positive int durationSeconds) {
        return ResponseEntity.ok(videoPricingService.quote(tenantId, durationSeconds));
    }
}
