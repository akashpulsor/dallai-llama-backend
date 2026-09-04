package com.dalai.llama.billing.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/**
 * billing-service -> creative-planning-service, read-only: the price a project was actually
 * quoted/sold at lives on {@code ProjectRequirement} there, not in billing-service or
 * pre-production-service -- see creative-planning-service's {@code
 * InternalProjectPricingController} for the {@code Project.id -> LockedIdea -> ProjectRequirement}
 * join this resolves. Used only by {@link com.dalai.llama.billing.service.ProjectSpendService} to
 * evaluate the per-project spend cap.
 */
@Component
public class CreativePlanningServiceClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public CreativePlanningServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${billing.creative-planning.base-url}") String baseUrl,
            @Value("${billing.creative-planning.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    /** Null if this project didn't originate from a quoted requirement -- a legitimate case
     * (the spend cap simply doesn't apply), not an error. */
    public BigDecimal getQuotedTotalPrice(UUID tenantId, UUID projectId) {
        try {
            QuotedPriceResponse response = webClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/quoted-price", tenantId, projectId)
                    .retrieve()
                    .bodyToMono(QuotedPriceResponse.class)
                    .block(Duration.ofMillis(timeoutMs));
            return response == null ? null : response.quotedTotalPrice();
        } catch (WebClientResponseException.NotFound ex) {
            return null;
        }
    }

    private record QuotedPriceResponse(BigDecimal quotedTotalPrice) {}
}
