package com.dalai.llama.billing.controller;

import com.dalai.llama.billing.service.CreatorPayoutService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * pre-production-service -> billing: a package locked with nothing left to pay (the brief was paid
 * in full) has no lock payment to open its payout, so pre-production opens it here. A paid lock
 * opens it in {@link com.dalai.llama.billing.service.ClientReviewPaymentService} on capture.
 */
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}/creator-payouts")
@RequiredArgsConstructor
@Hidden
public class InternalCreatorPayoutController {

    private final CreatorPayoutService creatorPayoutService;

    @PostMapping("/{projectId}")
    public ResponseEntity<Void> open(@PathVariable UUID tenantId, @PathVariable UUID projectId) {
        creatorPayoutService.open(tenantId, projectId);
        return ResponseEntity.noContent().build();
    }
}
