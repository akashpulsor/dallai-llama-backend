package com.dalai.llama.preprod.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.UUID;

/**
 * pre-production-service -> product-service, server-side authorization for creator-video
 * entitlements. Same cross-service-client convention as {@link
 * com.dalai.llama.preprod.service.revenue.BillingClient} (thin caller of an internal endpoint,
 * that service owns the actual decision).
 *
 * <p>This is the enforcement point, not the frontend's own entitlement fetch -- creator-ui checks
 * entitlements to decide what to show, but a client can't be trusted to actually enforce anything;
 * every gated mutation here (cast voice/image upload today) re-checks against product-service at
 * the moment of the action.
 */
@Component
public class CreatorVideoEntitlementClient {

    public record Entitlements(
            boolean videoCreationEnabled,
            boolean videoDownloadEnabled,
            boolean editsEnabled,
            boolean imageUploadEnabled,
            boolean upscalingEnabled,
            boolean upscalePreviewEnabled,
            boolean characterVoiceUploadEnabled,
            boolean briefUrlShareEnabled
    ) {}

    private record EntitlementsResponse(UUID tenantId, UUID subscriptionId, String planCode,
                                         String planName, String status, String currentPeriodEnd,
                                         Entitlements entitlements) {}

    private final WebClient webClient;
    private final int timeoutMs;

    public CreatorVideoEntitlementClient(
            WebClient.Builder webClientBuilder,
            @Value("${pre-production.product.base-url}") String baseUrl,
            @Value("${pre-production.product.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public Entitlements get(UUID tenantId) {
        try {
            EntitlementsResponse response = webClient.get()
                    .uri("/api/v1/internal/products/tenants/{tenantId}/creator-video/entitlements", tenantId)
                    .retrieve()
                    .bodyToMono(EntitlementsResponse.class)
                    .block(Duration.ofMillis(timeoutMs));
            if (response == null || response.entitlements() == null) {
                throw PreProductionException.upstream("product-service returned no creator-video entitlements for tenant " + tenantId);
            }
            return response.entitlements();
        } catch (WebClientResponseException ex) {
            throw PreProductionException.upstream(
                    "product-service call failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }

    /** Throws 403 if the tenant's current plan doesn't include this feature -- call at the moment
     * of the gated action (upload, edit, upscale, share), not just once on page load. */
    public void require(UUID tenantId, boolean entitled, String featureName) {
        if (!entitled) {
            throw PreProductionException.forbidden(
                    "Your current plan does not include " + featureName + " -- upgrade to unlock it.");
        }
    }
}
