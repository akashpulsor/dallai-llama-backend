package com.dalai.llama.tenant.controller;


import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.dto.response.ProductAppsResponse;
import com.dalai.llama.tenant.repository.TenantRepository;

import com.dalai.llama.tenant.service.client.BillingServiceClient;
import com.dalai.llama.tenant.service.client.ProductServiceClient;
import com.dalai.llama.tenant.util.TenantContext;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tenant Apps endpoint - returns apps for launch screen.
 * Lives in tenant-service (has tenant context).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tenant")
@RequiredArgsConstructor
public class TenantAppsController {

    private final TenantRepository tenantRepository;
    private final ProductServiceClient productClient;
    private final BillingServiceClient billingClient;

    /**
     * Get current tenant's apps and status
     *
     * GET /api/v1/tenant/apps
     */
    @GetMapping("/apps")
    public ResponseEntity<TenantAppsResponse> getApps(@AuthenticationPrincipal Jwt jwt) {

        UUID tenantId = TenantContext.getTenantId(jwt);

        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) {
            return ResponseEntity.notFound().build();
        }

        // Get subscription from product-service
        ProductServiceClient.TenantSubscriptionInfo subscription = productClient.getSubscription(tenantId);
        if (subscription == null) {
            return ResponseEntity.ok(TenantAppsResponse.builder()
                    .status("NO_SUBSCRIPTION")
                    .tenant(TenantInfo.builder()
                            .id(tenant.getId())
                            .name(tenant.getName())
                            .slug(tenant.getSlug())
                            .build())
                    .build());
        }

        // Get apps from product-service
        List<ProductServiceClient.AppInfo> appInfos = productClient.geTenantProductApps(tenantId);

        // Build full URLs
        List<TenantAppInfo> apps = appInfos.stream()
                .map(app -> TenantAppInfo.builder()
                        .type(app.type())
                        .displayName(app.displayName())
                        .url(buildAppUrl(app.subdomain(), tenant.getSlug()))
                        .icon(app.icon())
                        .enabled(true)
                        .build())
                .toList();

        // Get wallet balance
        BigDecimal balance = billingClient.getBalance(tenantId);

        // Determine status
        String status = determineStatus(subscription);

        return ResponseEntity.ok(TenantAppsResponse.builder()
                .status(status)
                .tenant(TenantInfo.builder()
                        .id(tenant.getId())
                        .name(tenant.getName())
                        .slug(tenant.getSlug())
                        .build())
                .product(ProductInfo.builder()
                        .code(subscription.productCode())
                        .build())
                .plan(PlanInfo.builder()
                        .code(subscription.planCode())
                        .name(subscription.planName())
                        .tier(subscription.planTier())
                        .validUntil(subscription.validUntil())
                        .nextBillingDate(subscription.nextBillingDate())
                        .build())
                .did(subscription.primaryDid() != null ? DidInfo.builder()
                        .number(subscription.primaryDid().number())
                        .displayNumber(subscription.primaryDid().displayNumber())
                        .status(subscription.primaryDid().status())
                        .build() : null)
                .apps(apps)
                .stats(QuickStats.builder()
                        .agentSeats(subscription.maxAgents())
                        .channelsAvailable(subscription.channelsAvailable())
                        .channelsInUse(subscription.channelsInUse())
                        .aiMinutesIncluded(subscription.includedMinutes())
                        .walletBalance(balance)
                        .build())
                .build());
    }

    private String buildAppUrl(String subdomain, String tenantSlug) {
        return String.format("https://%s.%s.dalaillama.in", subdomain, tenantSlug);
    }

    private String determineStatus(ProductServiceClient.TenantSubscriptionInfo sub) {
        if (sub.primaryDid() == null) return "PENDING_DID";
        if ("PENDING".equals(sub.primaryDid().status())) return "PENDING_ACTIVATION";
        return "ACTIVE";
    }

    // Response DTOs

    @Builder
    public record TenantAppsResponse(
            String status,
            TenantInfo tenant,
            ProductInfo product,
            PlanInfo plan,
            DidInfo did,
            List<TenantAppInfo> apps,
            QuickStats stats
    ) {}

    @Builder
    public record TenantInfo(
            UUID id,
            String name,
            String slug
    ) {}

    @Builder
    public record ProductInfo(
            String code
    ) {}

    @Builder
    public record PlanInfo(
            String code,
            String name,
            String tier,
            Instant validUntil,
            Instant nextBillingDate
    ) {}

    @Builder
    public record DidInfo(
            String number,
            String displayNumber,
            String status
    ) {}

    @Builder
    public record TenantAppInfo(
            String type,
            String displayName,
            String url,
            String icon,
            boolean enabled
    ) {}

    @Builder
    public record QuickStats(
            int agentSeats,
            int channelsAvailable,
            int channelsInUse,
            int aiMinutesIncluded,
            BigDecimal walletBalance
    ) {}
}