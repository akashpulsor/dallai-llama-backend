package com.dalai.llama.product.controller;

import com.dalai.llama.product.domain.entity.Subscription;
import com.dalai.llama.product.dto.request.SubscriptionRequest;
import com.dalai.llama.product.dto.response.SubscriptionResponse;

import com.dalai.llama.product.service.impl.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
@Tag(name = "Subscriptions", description = "Product subscription management")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    /**
     * Subscribe to a product
     *
     * POST /api/v1/subscriptions
     */
    @PostMapping
    @Operation(summary = "Subscribe to product", description = "Create new subscription for a product")
    public ResponseEntity<SubscriptionResponse> subscribe(@Valid @RequestBody SubscriptionRequest request) {
        log.info("Subscription request for tenant {} - product: {}, plan: {}",
                request.getTenantId(), request.getProductCode(), request.getPlanCode());

        SubscriptionResponse response = subscriptionService.subscribe(request);

        HttpStatus status = "PENDING_PAYMENT".equals(response.getStatus())
                ? HttpStatus.ACCEPTED
                : HttpStatus.CREATED;

        return ResponseEntity.status(status).body(response);
    }

    /**
     * Retry failed provisioning
     *
     * POST /api/v1/subscriptions/{id}/retry
     */
    @PostMapping("/{subscriptionId}/retry")
    @Operation(summary = "Retry provisioning", description = "Retry provisioning for stuck subscription")
    public ResponseEntity<SubscriptionResponse> retryProvisioning(@PathVariable UUID subscriptionId) {
        log.info("Retry provisioning for subscription {}", subscriptionId);
        SubscriptionResponse response = subscriptionService.retryProvisioning(subscriptionId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get subscription details
     *
     * GET /api/v1/subscriptions/{id}
     */
    @GetMapping("/{subscriptionId}")
    @Operation(summary = "Get subscription", description = "Get subscription details")
    public ResponseEntity<SubscriptionResponse> getSubscription(@PathVariable UUID subscriptionId) {
        SubscriptionResponse response = subscriptionService.getSubscriptionDetails(subscriptionId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all subscriptions for tenant
     *
     * GET /api/v1/subscriptions?tenantId={tenantId}
     */
    @GetMapping
    @Operation(summary = "List subscriptions", description = "Get all subscriptions for tenant")
    public ResponseEntity<List<SubscriptionSummary>> listSubscriptions(@RequestParam UUID tenantId) {
        List<Subscription> subs = subscriptionService.getTenantSubscriptions(tenantId);

        List<SubscriptionSummary> summaries = subs.stream()
                .map(this::toSummary)
                .toList();

        return ResponseEntity.ok(summaries);
    }

    /**
     * Cancel subscription
     *
     * DELETE /api/v1/subscriptions/{id}
     */
    @DeleteMapping("/{subscriptionId}")
    @Operation(summary = "Cancel subscription", description = "Cancel a subscription")
    public ResponseEntity<Void> cancelSubscription(@PathVariable UUID subscriptionId) {
        // TODO: Implement cancellation
        return ResponseEntity.noContent().build();
    }

    private SubscriptionSummary toSummary(Subscription sub) {
        return SubscriptionSummary.builder()
                .id(sub.getId())
                .productCode(sub.getProduct().getCode())
                .productName(sub.getProduct().getName())
                .planCode(sub.getPlan().getCode())
                .planName(sub.getPlan().getName())
                .status(sub.getStatus().name())
                .agentSeats(sub.getAgentSeats())
                .subscribedAt(sub.getSubscribedAt())
                .activatedAt(sub.getActivatedAt())
                .expiresAt(sub.getExpiresAt())
                .fullyProvisioned(sub.isFullyProvisioned())
                .build();
    }

    @lombok.Builder
    @lombok.Data
    public static class SubscriptionSummary {
        private UUID id;
        private String productCode;
        private String productName;
        private String planCode;
        private String planName;
        private String status;
        private int agentSeats;
        private java.time.Instant subscribedAt;
        private java.time.Instant activatedAt;
        private java.time.Instant expiresAt;
        private boolean fullyProvisioned;
    }
}