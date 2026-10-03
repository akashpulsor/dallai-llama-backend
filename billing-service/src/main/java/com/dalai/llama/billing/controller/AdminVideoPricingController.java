package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.service.VideoPricingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/** The ops page's Pricing tab: read and set the per-second video rate, and preview what a client
 * would pay for a brief of a given length under it. Same perimeter as {@link
 * AdminBillingController} (ops-dashboard oauth2-proxy on the gateway). A new rate re-prices new
 * briefs only -- every brief snapshots its quote when it is created. */
@Validated
@RestController
@RequestMapping("/api/v1/internal/admin/billing/video-pricing")
@RequiredArgsConstructor
public class AdminVideoPricingController {

    private final VideoPricingService videoPricingService;

    @GetMapping
    public ResponseEntity<RateView> rate() {
        return ResponseEntity.ok(rateView());
    }

    @PutMapping
    public ResponseEntity<RateView> updateRate(@Valid @RequestBody UpdateRateRequest request) {
        videoPricingService.updateRatePerSecond(request.ratePerSecondInr());
        return ResponseEntity.ok(rateView());
    }

    @GetMapping("/preview")
    public ResponseEntity<VideoPricingService.PricePreview> preview(
            @RequestParam UUID tenantId, @RequestParam @Min(1) @Max(600) int durationSeconds) {
        return ResponseEntity.ok(videoPricingService.preview(tenantId, durationSeconds));
    }

    private RateView rateView() {
        return new RateView(videoPricingService.currentRatePerSecond(), videoPricingService.defaultRatePerSecond());
    }

    public record RateView(BigDecimal ratePerSecondInr, BigDecimal defaultRatePerSecondInr) {}

    public record UpdateRateRequest(@NotNull @DecimalMin(value = "0.01") BigDecimal ratePerSecondInr) {}
}
