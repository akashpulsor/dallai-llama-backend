package com.dalai.llama.postprod.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.UUID;

/**
 * post-production-service -> product-service, server-side authorization for creator-video
 * entitlements (upscaling today). Structural twin of pre-production-service's client of the same
 * name -- no shared module between services, same convention already established there.
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
            @Value("${post-production.product.base-url}") String baseUrl,
            @Value("${post-production.product.timeout-ms}") int timeoutMs
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
                throw PostProductionException.upstream("product-service returned no creator-video entitlements for tenant " + tenantId);
            }
            return response.entitlements();
        } catch (WebClientResponseException ex) {
            throw PostProductionException.upstream(
                    "product-service call failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }

    public void require(UUID tenantId, boolean entitled, String featureName) {
        if (!entitled) {
            throw PostProductionException.forbidden(
                    "Your current plan does not include " + featureName + " -- upgrade to unlock it.");
        }
    }
}
