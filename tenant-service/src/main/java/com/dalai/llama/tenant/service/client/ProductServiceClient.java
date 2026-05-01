package com.dalai.llama.tenant.service.client;

import com.dalai.llama.tenant.dto.response.PlanEntitlementResponse;
import com.dalai.llama.tenant.dto.response.ProductAppsResponse;
import com.dalai.llama.tenant.dto.response.ProductConfigResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client for Product Service - fetches product and app configurations
 */
@Slf4j
@Component
public class ProductServiceClient {

    private final WebClient.Builder webClientBuilder;
    private final String productServiceUrl;

    public ProductServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${services.product.url:http://product-service:8080}") String productServiceUrl) {
        this.webClientBuilder = webClientBuilder;
        this.productServiceUrl = productServiceUrl;
    }

    private WebClient client() {
        return webClientBuilder.baseUrl(productServiceUrl).build();
    }

    /**
     * Check if tenant has purchased a DID
     */
    public boolean hasPurchasedDid(UUID tenantId) {
        try {
            Boolean result = client().get()
                    .uri("/api/v1/internal/tenants/{id}/dids", tenantId)
                    .retrieve()
                    .bodyToMono(Boolean.class)
                    .block();
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Failed to check DID purchase for tenant {}: {}", tenantId, e.getMessage());
            return false;
        }
    }

    /**
     * Assign default plan to tenant
     */
    public boolean assignDefaultPlan(UUID tenantId, String productCode) {
        try {
            Boolean result = client().post()
                    .uri("/api/v1/internal/tenants/{id}/assign-default-plan?productCode={code}", tenantId, productCode)
                    .retrieve()
                    .bodyToMono(Boolean.class)
                    .block();
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("Failed to assign default plan for tenant {}: {}", tenantId, e.getMessage());
            return false;
        }
    }



    /**
     * Get subscription details for tenant
     */
    public TenantSubscriptionInfo getSubscription(UUID tenantId) {
        try {
            return client().get()
                    .uri("/api/v1/internal/tenants/{tenantId}/subscription", tenantId)
                    .retrieve()
                    .bodyToMono(TenantSubscriptionInfo.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to get subscription for tenant {}: {}", tenantId, e.getMessage());
            return null;
        }
    }

    /**
     * Get apps configured for tenant's product
     */
    public List<AppInfo> geTenantProductApps(UUID tenantId) {
        try {
            return client().get()
                    .uri("/api/v1/internal/products/tenants/{tenantId}/product-apps", tenantId)
                    .retrieve()
                    .bodyToFlux(AppInfo.class)
                    .collectList()
                    .block();
        } catch (Exception e) {
            log.error("Failed to get apps for tenant {}: {}", tenantId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Get complete subscription configuration.
     *
     * This is the MAIN method - gets everything in one call:
     * - Product info
     * - Plan info
     * - Entitlements (all feature flags)
     * - AI config
     * - Apps
     *
     * Cached for 5 minutes.
     */
    @Cacheable(value = "product-config", key = "#subscriptionId.toString()", unless = "#result == null")
    public ProductConfigResponse getSubscriptionConfig(UUID subscriptionId) {
        log.debug("Fetching subscription config from product-service: {}", subscriptionId);

        try {
            return client()
                    .get()
                    .uri("/api/v1/internal/products/subscriptions/{id}/config", subscriptionId)
                    .retrieve()
                    .onStatus(status -> status.equals(HttpStatus.NOT_FOUND),
                            response -> Mono.error(new RuntimeException("Subscription not found: " + subscriptionId)))
                    .bodyToMono(ProductConfigResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
        } catch (Exception e) {
            log.error("Failed to get subscription config: {}", e.getMessage());
            throw new RuntimeException("Failed to get subscription config from product-service", e);
        }
    }

    // ══════════════════════════════════════════════════════════════
// ADD THIS METHOD TO ProductServiceClient.java
// ══════════════════════════════════════════════════════════════

    /**
     * Fetch full plan entitlements by planId.
     * Called during provisioning to resolve all feature flags.
     */
    public PlanEntitlementResponse getPlanEntitlements(UUID planId) {
        log.info("Fetching plan entitlements for plan {}", planId);
        try {
            return client().get()
                    .uri("/api/v1/internal/products/plans/{planId}/entitlements", planId)
                    .retrieve()
                    .bodyToMono(PlanEntitlementResponse.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
        } catch (Exception e) {
            log.error("Failed to fetch entitlements for plan {}: {}", planId, e.getMessage());
            throw new RuntimeException("Failed to fetch plan entitlements: " + e.getMessage(), e);
        }
    }

    /**
     * Get entitlements for a plan (when subscription ID not available).
     */
    public PlanEntitlementResponse getPlanEntitlements(String planCode) {
        log.debug("Fetching plan entitlements from product-service: {}", planCode);

        try {
            return client()
                    .get()
                    .uri("/api/v1/internal/products/plans/{code}/entitlements", planCode)
                    .retrieve()
                    .bodyToMono(PlanEntitlementResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
        } catch (Exception e) {
            log.error("Failed to get plan entitlements: {}", e.getMessage());
            throw new RuntimeException("Failed to get plan entitlements from product-service", e);
        }
    }

    /**
     * Get product apps.
     */
    @Cacheable(value = "product-apps", key = "#productCode", unless = "#result == null")
    public ProductAppsResponse getProductApps(String productCode) {
        log.debug("Fetching product apps from product-service: {}", productCode);

        try {
            return client()
                    .get()
                    .uri("/api/v1/internal/products/{code}/apps", productCode)
                    .retrieve()
                    .bodyToMono(ProductAppsResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
        } catch (Exception e) {
            log.error("Failed to get product apps: {}", e.getMessage());
            throw new RuntimeException("Failed to get product apps from product-service", e);
        }
    }

    /**
     * Invalidate entitlement cache (called when plan changes).
     */
    public void invalidateCache(UUID tenantId) {
        log.debug("Requesting cache invalidation for tenant: {}", tenantId);

        try {
            client()
                    .post()
                    .uri("/api/v1/internal/tenants/{id}/entitlements/invalidate", tenantId)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
        } catch (Exception e) {
            log.warn("Failed to invalidate cache: {}", e.getMessage());
            // Don't throw - cache will expire naturally
        }
    }


    // Response DTOs

    public record TenantSubscriptionInfo(
            UUID planAssignmentId,
            String planCode,
            String planName,
            String planTier,
            String productCode,
            Instant validUntil,
            Instant nextBillingDate,
            // Entitlements
            int maxAgents,
            int maxDids,
            int maxChannels,
            int includedMinutes,
            BigDecimal aiRatePerMin,
            // DID info
            DidInfo primaryDid,
            // Channel info
            int channelsAvailable,
            int channelsInUse
    ) {}

    public record DidInfo(
            UUID id,
            String number,
            String displayNumber,
            String status
    ) {}

    public record AppInfo(
            String type,
            String subdomain,
            String displayName,
            String icon
    ) {}
    // ========================================================================
    // DTOs
    // ========================================================================

    @Data
    public static class ProductDto {
        private UUID id;
        private String code;
        private String name;
        private String description;
        private String type;  // ProductType enum as string
        private boolean active;
        private Map<String, Object> features;
    }

    @Data
    public static class ProductAppDto {
        private UUID id;
        private String appType;
        private String subdomain;
        private String displayName;
        private String keycloakClientSuffix;
        private String frontendImage;
        private Integer frontendPort;
        private String requiredRoles;
        private String icon;
        private Integer displayOrder;
    }

    // ==================== RESPONSE DTOs ====================
    // Mirror the product-service DTOs



}
