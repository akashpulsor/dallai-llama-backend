package com.dalai.llama.product.controller;

import com.dalai.llama.product.domain.entity.*;
import com.dalai.llama.product.domain.entity.enums.SubscriptionStatus;
import com.dalai.llama.product.dto.mapper.ProductMapper;
import com.dalai.llama.product.dto.response.*;
import com.dalai.llama.product.repository.*;
import com.dalai.llama.product.service.EntitlementService;
import com.dalai.llama.product.service.PlanAssignmentService;
import com.dalai.llama.product.service.didww.DidwwApiService;
import com.dalai.llama.product.service.didww.DidwwProvisioningService;
import com.dalai.llama.product.service.impl.SubscriptionService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/v1/internal/products")
@RequiredArgsConstructor
@Tag(name = "Internal APIs", description = "Service-to-service APIs")
@Hidden
public class InternalProductController {

    private final PlanAssignmentService planAssignmentService;
    private final EntitlementService entitlementService;
    private final PlanRepository planRepository;
    private final DidRepository didRepository;
    private final PstnChannelBundleRepository channelBundleRepository;
    private final ProductMapper mapper;
    private final DidwwProvisioningService didwwProvisioningService;
    private final SubscriptionRepository subscriptionRepository;
    private final ProductRepository productRepository;
    private final PlanEntitlementRepository planEntitlementRepository;
    private final TenantSipTrunkRepository tenantSipTrunkRepository;
    private  final PlanAssignmentRepository planAssignmentRepository;
    private final ProductAppRepository productAppRepository;
    private final SubscriptionService subscriptionService;
    // ==================== SUBSCRIPTION ENDPOINTS ====================

    @PostMapping("/subscriptions/{subscriptionId}/activate")
    @Operation(summary = "Activate subscription after payment success")
    public ResponseEntity<SubscriptionResponse> activateSubscription(
            @PathVariable UUID subscriptionId
    ) {
        log.info("Activating subscription {}", subscriptionId);

        SubscriptionResponse response =
                subscriptionService.postSubscription(subscriptionId);

        return ResponseEntity.ok(response);
    }

    /**
     * Called by Tenant Service during provisioning to assign default plan
     */
    @PostMapping("/tenants/{tenantId}/default-plan")
    @Operation(summary = "Assign default plan to tenant")
    public ResponseEntity<PlanAssignmentResponse> assignDefaultPlan(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "AI_CC") String productCode
    ) {
        log.info("Assigning default plan for tenant {} with product {}", tenantId, productCode);

        // Find default plan for the product
        Plan defaultPlan = planRepository.findByProduct_CodeAndIsDefaultTrue(productCode)
                .orElse(null);

        if (defaultPlan == null) {
            log.warn("No default plan found for product {}", productCode);
            return ResponseEntity.notFound().build();
        }

        var assignment = planAssignmentService.assignPlan(tenantId, defaultPlan.getId());
        log.info("Assigned default plan {} to tenant {}", defaultPlan.getCode(), tenantId);

        return ResponseEntity.ok(mapper.toPlanAssignmentResponse(assignment));
    }

    /**
     * Called by Tenant Service during deprovisioning (compensation)
     */
    @DeleteMapping("/tenants/{tenantId}/plan")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove plan assignment (compensation)")
    public void removePlan(@PathVariable UUID tenantId) {
        log.info("Removing plan for tenant {}", tenantId);
        planAssignmentService.removePlan(tenantId);
        entitlementService.invalidateCache(tenantId);
    }

    /**
     * Invalidate entitlement cache for a tenant.
     * Called when plan changes or subscription is modified.
     */
    @PostMapping("/tenants/{tenantId}/cache/invalidate")
    @Operation(summary = "Invalidate cache for tenant")
    public ResponseEntity<Void> invalidateCache(@PathVariable UUID tenantId) {
        log.info("Invalidating cache for tenant {}", tenantId);
        entitlementService.invalidateCache(tenantId);
        return ResponseEntity.ok().build();
    }

    /**
     * Called by PBX Core / Billing for authorization checks
     */
    @GetMapping("/tenants/{tenantId}/entitlements")
    @Operation(summary = "Get tenant entitlements for authorization")
    public EntitlementResponse getEntitlements(@PathVariable UUID tenantId) {
        return mapper.toEntitlementResponse(
                entitlementService.getEffectiveEntitlements(tenantId)
        );
    }

    /**
     * Called by PBX Core to get DID info by number
     */
    @GetMapping("/tenants/{tenantId}/dids/{number}")
    @Operation(summary = "Get DID by number")
    public ResponseEntity<DidResponse> getDidByNumber(
            @PathVariable UUID tenantId,
            @PathVariable String number
    ) {
        return didRepository.findByTenantIdAndNumber(tenantId, number)
                .map(mapper::toDidResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Called by Tenant Service to check purchased DIDs
     */
    @GetMapping("/tenants/{tenantId}/dids/purchased")
    @Operation(summary = "Get purchased DIDs for tenant")
    public List<DidResponse> getPurchasedDids(@PathVariable UUID tenantId) {
        return didRepository.findByTenantId(tenantId).stream()
                .map(mapper::toDidResponse)
                .toList();
    }

    /**
     * Called by PBX Core to get available PSTN channels
     */
    @GetMapping("/tenants/{tenantId}/channels")
    @Operation(summary = "Get available PSTN channels")
    public ResponseEntity<Map<String, Object>> getAvailableChannels(@PathVariable UUID tenantId) {
        var bundles = channelBundleRepository.findByTenantId(tenantId);

        int totalChannels = bundles.stream().mapToInt(b -> b.getTotalChannels()).sum();
        int activeChannels = bundles.stream().mapToInt(b -> b.getActiveChannels()).sum();
        int inboundChannels = bundles.stream().mapToInt(b -> b.getInboundChannels()).sum();
        int outboundChannels = bundles.stream().mapToInt(b -> b.getOutboundChannels()).sum();

        return ResponseEntity.ok(Map.of(
                "totalChannels", totalChannels,
                "activeChannels", activeChannels,
                "availableChannels", totalChannels - activeChannels,
                "inboundChannels", inboundChannels,
                "outboundChannels", outboundChannels
        ));
    }

    /**
     * Called by Tenant Service to configure DIDWW trunk with Kamailio IP
     */
    @PostMapping("/{tenantId}/didww/configure")
    @Operation(summary = "Configure DIDWW trunk with Kamailio IP")
    public ResponseEntity<Map<String, String>> configureDidwwTrunk(
            @PathVariable UUID tenantId,
            @RequestBody Map<String, String> request
    ) {
        String kamailioIp = request.get("kamailioIp");
        int kamailioPort = Integer.parseInt(request.getOrDefault("kamailioPort", "5060"));

        log.info("Configuring DIDWW trunk for tenant {} with IP {}:{}", tenantId, kamailioIp, kamailioPort);


        didwwProvisioningService.configureSip(tenantId.toString(), kamailioIp, kamailioPort);
        return ResponseEntity.ok(Map.of(
                "status", "configured",
                "kamailioIp", kamailioIp,
                "kamailioPort", String.valueOf(kamailioPort)
        ));
    }

    /**
     * Get all subscriptions for tenant
     * GET /api/v1/internal/tenants/{tenantId}/subscriptions
     */
    @GetMapping("/{tenantId}/subscriptions")
    public ResponseEntity<List<TenantSubscriptionInfo>> getSubscriptions(@PathVariable UUID tenantId) {
        List<Subscription> subscriptions = subscriptionRepository.findByTenantId(tenantId);

        List<TenantSubscriptionInfo> result = subscriptions.stream()
                .map(this::buildSubscriptionInfo)
                .toList();

        return ResponseEntity.ok(result);
    }

    /**
     * Get active subscriptions only
     * GET /api/v1/internal/products/{tenantId}/subscriptions?status=ACTIVE
     */
    @GetMapping("/{tenantId}/subscriptions/active")
    public ResponseEntity<List<TenantSubscriptionInfo>> getActiveSubscriptions(@PathVariable UUID tenantId) {
        List<Subscription> subscriptions = subscriptionRepository.findByTenantIdAndStatus(tenantId, SubscriptionStatus.ACTIVE);

        List<TenantSubscriptionInfo> result = subscriptions.stream()
                .map(this::buildSubscriptionInfo)
                .toList();

        return ResponseEntity.ok(result);
    }

    /**
     * Get subscription by ID
     * GET /api/v1/internal/subscriptions/{subscriptionId}
     */
    @GetMapping("/subscriptions/{subscriptionId}")
    public ResponseEntity<TenantSubscriptionInfo> getSubscriptionById(@PathVariable UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElse(null);

        if (subscription == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(buildSubscriptionInfo(subscription));
    }

    /**
     * Get subscriptions by product code (can be multiple)
     * GET /api/v1/internal/tenants/{tenantId}/subscriptions/product/{productCode}
     */
    @GetMapping("/{tenantId}/subscriptions/product/{productCode}")
    public ResponseEntity<List<TenantSubscriptionInfo>> getSubscriptionsByProduct(
            @PathVariable UUID tenantId,
            @PathVariable String productCode) {

        Product product = productRepository.findByCode(productCode).orElse(null);
        if (product == null) {
            return ResponseEntity.ok(List.of());
        }

        List<Subscription> subscriptions = subscriptionRepository.findByTenantIdAndProductId(tenantId, product.getId());

        List<TenantSubscriptionInfo> result = subscriptions.stream()
                .map(this::buildSubscriptionInfo)
                .toList();

        return ResponseEntity.ok(result);
    }

    /**
     * Get subscription by DID number
     * GET /api/v1/internal/tenants/{tenantId}/subscriptions/did/{didNumber}
     */
    @GetMapping("/{tenantId}/subscriptions/did/{didNumber}")
    public ResponseEntity<TenantSubscriptionInfo> getSubscriptionByDid(
            @PathVariable UUID tenantId,
            @PathVariable String didNumber) {

        Did did = didRepository.findByNumber(didNumber).orElse(null);
        if (did == null || !did.getTenantId().equals(tenantId)) {
            return ResponseEntity.notFound().build();
        }

        Subscription subscription = subscriptionRepository.findByDidId(did.getId()).orElse(null);
        if (subscription == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(buildSubscriptionInfo(subscription));
    }


    /**
     * Get COMPLETE subscription configuration.
     *
     * This is the MAIN endpoint used by tenant-service.
     * Returns everything needed to provision a tenant:
     * - Product info (code, name, description)
     * - Plan info (tier, pricing)
     * - Entitlements (ALL feature flags)
     * - AI config (providers, costs)
     * - Apps (ALL UI apps with icons, images, roles)
     *
     * Cached for 5 minutes.
     */
    @GetMapping("/subscriptions/{subscriptionId}/config")
    @Operation(summary = "Get complete configuration for subscription")
    public ResponseEntity<ProductConfigResponse> getSubscriptionConfig(
            @PathVariable UUID subscriptionId) {

        log.debug("Internal API: Getting complete config for subscription {}", subscriptionId);

        ProductConfigResponse config = entitlementService.getSubscriptionConfig(subscriptionId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES))
                .body(config);
    }
    /**
     * Get entitlements for a subscription.
     * Use getSubscriptionConfig() instead for complete data.
     */
    @GetMapping("/subscriptions/{subscriptionId}/entitlements")
    @Operation(summary = "Get entitlements for subscription")
    public ResponseEntity<PlanEntitlementResponse> getSubscriptionEntitlements(
            @PathVariable UUID subscriptionId) {

        log.debug("Internal API: Getting entitlements for subscription {}", subscriptionId);

        PlanEntitlementResponse entitlements = entitlementService.getEntitlementsForSubscription(subscriptionId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES))
                .body(entitlements);
    }

    /**
     * Get entitlements by plan code.
     * Used when subscription ID is not yet available.
     */
    @GetMapping("/plans/{planCode}/entitlements")
    @Operation(summary = "Get entitlements for plan")
    public ResponseEntity<PlanEntitlementResponse> getPlanEntitlements(
            @PathVariable String planCode) {

        log.debug("Internal API: Getting entitlements for plan {}", planCode);

        // Support both planCode (string) and planId (UUID) in the same path variable
        PlanEntitlementResponse entitlements;
        try {
            UUID planId = UUID.fromString(planCode);
            entitlements = entitlementService.getEntitlementsForPlanId(planId);
        } catch (IllegalArgumentException e) {
            entitlements = entitlementService.getEntitlementsForPlan(planCode);
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES))
                .body(entitlements);
    }

    /**
     * Get product apps configuration.
     */
    @GetMapping("/products/{productCode}/apps")
    @Operation(summary = "Get apps for product")
    public ResponseEntity<ProductAppsResponse> getProductApps(
            @PathVariable String productCode) {

        log.debug("Internal API: Getting apps for product {}", productCode);

        ProductAppsResponse apps = entitlementService.getProductApps(productCode);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES))
                .body(apps);
    }

    /**
     * Get product apps for tenant
     * Called by tenant-service to build app URLs
     */
    @GetMapping("/{tenantId}/product-apps")
    public ResponseEntity<List<SubscriptionResponse.AppInfo>> getProductApps(@PathVariable UUID tenantId) {

        PlanAssignment assignment = planAssignmentRepository.findActiveByTenantId(tenantId)
                .orElse(null);

        if (assignment == null) {
            return ResponseEntity.ok(List.of());
        }

        UUID productId = assignment.getPlan().getProduct().getId();

        List<SubscriptionResponse.AppInfo> apps = productAppRepository
                .findByProductIdAndEnabledTrueOrderByDisplayOrderAsc(productId)
                .stream()
                .map(app -> SubscriptionResponse.AppInfo.builder()
                        .type(app.getAppType().name())
                        .url(app.getSubdomain())
                        .displayName(app.getDisplayName())
                        .icon(app.getIcon())
                        .build())
                .toList();

        return ResponseEntity.ok(apps);
    }

    @SuppressWarnings("unchecked")
    private Integer getAgentOverride(PlanAssignment assignment) {
        if (assignment.getEntitlementOverrides() == null) return null;
        Object val = assignment.getEntitlementOverrides().get("agentCount");
        return val instanceof Number ? ((Number) val).intValue() : null;
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TenantSubscriptionInfo {
        private UUID subscriptionId;
        private UUID tenantId;
        // Product & Plan
        private String productCode;
        private String productName;
        private String planCode;
        private String planName;
        private String planTier;
        // Status & Dates
        private String status;
        private Instant subscribedAt;
        private Instant activatedAt;
        private Instant expiresAt;
        private Instant nextBillingDate;
        // Provisioning
        private boolean fullyProvisioned;
        private boolean didProvisioned;
        private boolean sipEndpointCreated;
        private boolean channelsAllocated;
        // Entitlements
        private int agentSeats;
        private int maxAgents;
        private int maxDids;
        private int maxChannels;
        private int includedMinutes;
        private BigDecimal aiRatePerMin;
        // Resources
        private DidInfo did;
        private ChannelInfo channels;
        private SipInfo sipIntegration;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DidInfo {
        private UUID id;
        private String number;
        private String displayNumber;
        private String country;
        private String region;
        private String city;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChannelInfo {
        private UUID id;
        private String direction;
        private int total;
        private Integer inbound;
        private Integer outbound;
        private int inUse;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SipInfo {
        private UUID id;
        private String server;
        private int port;
        private String username;
        private String realm;
    }

    // ==================== PRIVATE METHODS ====================

    private TenantSubscriptionInfo buildSubscriptionInfo(Subscription subscription) {
        Plan plan = subscription.getPlan();
        Product product = subscription.getProduct();

        // Get entitlements
        PlanEntitlement ent = planEntitlementRepository.findByPlan_Id(plan.getId()).orElse(null);

        // Get DID
        Did did = subscription.getDidId() != null
                ? didRepository.findById(subscription.getDidId()).orElse(null)
                : null;

        // Get channels
        PstnChannelBundle bundle = subscription.getChannelBundleId() != null
                ? channelBundleRepository.findById(subscription.getChannelBundleId()).orElse(null)
                : null;

        // Get SIP trunk
        TenantSipTrunk sipTrunk = subscription.getTenantSipTrunkId() != null
                ? tenantSipTrunkRepository.findById(subscription.getTenantSipTrunkId()).orElse(null)
                : null;

        return TenantSubscriptionInfo.builder()
                .subscriptionId(subscription.getId())
                .tenantId(subscription.getTenantId())
                // Product & Plan
                .productCode(product.getCode())
                .productName(product.getName())
                .planCode(plan.getCode())
                .planName(plan.getName())
                .planTier(plan.getTier().name())
                // Status & Dates
                .status(subscription.getStatus().name())
                .subscribedAt(subscription.getSubscribedAt())
                .activatedAt(subscription.getActivatedAt())
                .expiresAt(subscription.getExpiresAt())
                .nextBillingDate(subscription.getExpiresAt())
                // Provisioning
                .fullyProvisioned(subscription.isFullyProvisioned())
                .didProvisioned(subscription.isDidProvisioned())
                .sipEndpointCreated(subscription.isSipEndpointCreated())
                .channelsAllocated(subscription.isChannelsAllocated())
                // Entitlements
                .agentSeats(subscription.getAgentSeats())
                .maxAgents(ent != null ? ent.getMaxAgents() : subscription.getAgentSeats())
                .maxDids(ent != null ? ent.getMaxDids() : 1)
                .maxChannels(ent != null ? ent.getMaxPstnChannels() : 0)
                .includedMinutes(subscription.getIncludedMinutes())
                .aiRatePerMin(plan.getAiRatePerMin())
                // DID
                .did(did != null ? DidInfo.builder()
                        .id(did.getId())
                        .number(did.getNumber())
                        .displayNumber(did.getDisplayNumber())
                        .country(did.getCountry())
                        .region(did.getRegion())
                        .city(did.getCity())
                        .status(did.getStatus().name())
                        .build() : null)
                // Channels
                .channels(bundle != null ? ChannelInfo.builder()
                        .id(bundle.getId())
                        .direction(bundle.getDirection().name())
                        .total(bundle.getTotalChannels())
                        .inbound(bundle.getInboundChannels())
                        .outbound(bundle.getOutboundChannels())
                        .inUse(bundle.getActiveChannels())
                        .status(bundle.getStatus())
                        .build() : null)
                // SIP Integration
                .sipIntegration(sipTrunk != null ? SipInfo.builder()
                        .id(sipTrunk.getId())
                        .server(sipTrunk.getDomain())
                        .port(sipTrunk.getPort())
                        .username(sipTrunk.getUsername())
                        .realm(sipTrunk.getRealm())
                        .build() : null)
                .build();
    }

}