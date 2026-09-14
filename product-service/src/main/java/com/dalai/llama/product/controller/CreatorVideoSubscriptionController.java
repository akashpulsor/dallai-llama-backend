package com.dalai.llama.product.controller;

import com.dalai.llama.product.domain.entity.CreatorVideoPlanEntitlement;
import com.dalai.llama.product.domain.entity.Plan;
import com.dalai.llama.product.dto.creatorvideo.CreatorVideoEntitlements;
import com.dalai.llama.product.dto.creatorvideo.CreatorVideoEntitlementsResponse;
import com.dalai.llama.product.dto.creatorvideo.CreatorVideoPlanResponse;
import com.dalai.llama.product.dto.creatorvideo.CreatorVideoSubscribeRequest;
import com.dalai.llama.product.dto.creatorvideo.CreatorVideoSubscriptionResponse;
import com.dalai.llama.product.repository.CreatorVideoPlanEntitlementRepository;
import com.dalai.llama.product.repository.PlanRepository;
import com.dalai.llama.product.service.CreatorVideoEntitlementService;
import com.dalai.llama.product.service.impl.CreatorVideoSubscriptionService;
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

/**
 * Creator-video subscription lifecycle -- separate from {@link SubscriptionController} (the PBX
 * one) on purpose: that controller's request/response DTOs are DID/telephony-shaped and its
 * service unconditionally runs a DID/SIP provisioning saga this product has no use for.
 *
 * <p>Mounted under {@code /api/v1/products} (not a new top-level segment) because that's the only
 * prefix the platform's gateway actually routes to product-service publicly (see
 * {@code infra-platform/charts/gateway/values.yaml}'s {@code product} ingress rule) -- this is the
 * one controller here creator-ui (a browser SPA, reachable only through that public gateway) calls
 * directly, unlike {@code InternalProductController}'s {@code @Hidden} internal-network-only APIs.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/products/creator-video")
@RequiredArgsConstructor
@Tag(name = "Creator Video Subscriptions", description = "Subscription lifecycle for the creator-video product")
public class CreatorVideoSubscriptionController {

    private final CreatorVideoSubscriptionService subscriptionService;
    private final CreatorVideoEntitlementService entitlementService;
    private final PlanRepository planRepository;
    private final CreatorVideoPlanEntitlementRepository planEntitlementRepository;

    /** Plan catalog for the plan-picker UI -- prices/codes come from here, never hardcoded
     * client-side, so a future price change or a fourth cadence needs no frontend deploy. */
    @GetMapping("/plans")
    @Operation(summary = "List creator-video plans")
    public ResponseEntity<List<CreatorVideoPlanResponse>> listPlans() {
        List<Plan> plans = planRepository.findByProduct_CodeAndActiveTrue("CREATOR_VIDEO");
        List<CreatorVideoPlanResponse> response = plans.stream().map(this::toPlanResponse).toList();
        return ResponseEntity.ok(response);
    }

    private CreatorVideoPlanResponse toPlanResponse(Plan plan) {
        CreatorVideoEntitlements entitlements = planEntitlementRepository.findByPlan_Id(plan.getId())
                .map(this::toEntitlements)
                .orElseGet(CreatorVideoEntitlements::freeDefaults);
        return CreatorVideoPlanResponse.builder()
                .planCode(plan.getCode())
                .planName(plan.getName())
                .tier(plan.getTier().name())
                .billingCycle(plan.getBillingCycle() != null ? plan.getBillingCycle().name() : null)
                .price(plan.getMonthlyPrice())
                .currency(plan.getCurrency())
                .entitlements(entitlements)
                .build();
    }

    private CreatorVideoEntitlements toEntitlements(CreatorVideoPlanEntitlement e) {
        return new CreatorVideoEntitlements(
                e.isVideoCreationEnabled(), e.isVideoDownloadEnabled(), e.isEditsEnabled(),
                e.isImageUploadEnabled(), e.isUpscalingEnabled(), e.isUpscalePreviewEnabled(),
                e.isCharacterVoiceUploadEnabled(), e.isBriefUrlShareEnabled());
    }

    @PostMapping("/subscriptions")
    @Operation(summary = "Subscribe", description = "Charges the wallet immediately and activates the subscription, or returns INSUFFICIENT_BALANCE")
    public ResponseEntity<CreatorVideoSubscriptionResponse> subscribe(@Valid @RequestBody CreatorVideoSubscribeRequest request) {
        CreatorVideoSubscriptionResponse response = subscriptionService.subscribe(request);
        // PAYMENT_REQUIRED comes back 200, not 402. It is not a failure -- it carries a live
        // Razorpay order the caller is meant to act on, and every HTTP client in this codebase
        // treats a non-2xx as a rejected promise whose body is awkward to reach. Returning it as
        // an error is what made the browser drop the checkout details on the floor.
        HttpStatus status = "INSUFFICIENT_BALANCE".equals(response.status())
                ? HttpStatus.PAYMENT_REQUIRED
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/subscriptions/{subscriptionId}/cancel")
    @Operation(summary = "Cancel", description = "Immediate cancellation -- entitlements drop to the free tier right away")
    public ResponseEntity<Void> cancel(@PathVariable UUID subscriptionId) {
        subscriptionService.cancel(subscriptionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/subscriptions/{subscriptionId}/pause")
    @Operation(summary = "Pause", description = "Stops billing and drops entitlements to the free tier; resumable without re-subscribing")
    public ResponseEntity<Void> pause(@PathVariable UUID subscriptionId) {
        subscriptionService.pause(subscriptionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/subscriptions/{subscriptionId}/resume")
    @Operation(summary = "Resume", description = "Restores entitlements and starts a fresh billing cycle from now")
    public ResponseEntity<Void> resume(@PathVariable UUID subscriptionId) {
        subscriptionService.resume(subscriptionId);
        return ResponseEntity.noContent().build();
    }

    /** Primary "/me" lookup, per product decision: entitlements by subscription id. */
    @GetMapping("/subscriptions/{subscriptionId}/entitlements")
    @Operation(summary = "Get entitlements by subscription")
    public ResponseEntity<CreatorVideoEntitlementsResponse> getEntitlementsBySubscription(@PathVariable UUID subscriptionId) {
        return ResponseEntity.ok(entitlementService.getEntitlementsBySubscription(subscriptionId));
    }

    /** Convenience for creator-ui's initial load, which only has {@code tenantId} until it knows
     * whether (or what) the tenant has subscribed to -- resolves to the tenant's current
     * creator-video subscription, or the free tier if none exists yet. Same explicit-tenantId-in-
     * path convention billing-service's public wallet endpoints already use (no separate
     * ownership check here, matching that existing precedent -- the gateway's JWT auth is the
     * boundary, same as it is for those). */
    @GetMapping("/tenants/{tenantId}/entitlements")
    @Operation(summary = "Get entitlements by tenant")
    public ResponseEntity<CreatorVideoEntitlementsResponse> getEntitlementsByTenant(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(entitlementService.getEntitlementsByTenant(tenantId));
    }
}
