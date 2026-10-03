package com.dalai.llama.billing.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * billing-service -> creative-planning-service, read-only: the price a project was actually
 * quoted/sold at lives on {@code ProjectRequirement} there, not in billing-service or
 * pre-production-service -- see creative-planning-service's {@code
 * InternalProjectPricingController} for the {@code Project.id -> LockedIdea -> ProjectRequirement}
 * join this resolves. Read by {@link com.dalai.llama.billing.service.ProjectSpendService} (spend
 * cap) and {@link com.dalai.llama.billing.service.ClientReviewPaymentService} (lock balance).
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

    /** Empty if this project didn't originate from a quoted requirement -- a legitimate case
     * (no spend cap, flat lock price), not an error. */
    public Optional<ProjectQuote> getProjectQuote(UUID tenantId, UUID projectId) {
        try {
            return Optional.ofNullable(webClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/quoted-price", tenantId, projectId)
                    .retrieve()
                    .bodyToMono(ProjectQuote.class)
                    .block(Duration.ofMillis(timeoutMs)));
        } catch (WebClientResponseException.NotFound ex) {
            return Optional.empty();
        }
    }

    /** Structural mirror of creative-planning-service's {@code ProjectQuoteView}. */
    public record ProjectQuote(
            BigDecimal quotedTotalPrice,
            BigDecimal quotedCreatorMarginPercent,
            String quotedCurrency,
            BigDecimal requiredAmount,
            boolean funded
    ) {
        /** What the client already paid on the brief -- the upfront amount, only once funded. */
        public BigDecimal paidUpfront() {
            return funded && requiredAmount != null ? requiredAmount : BigDecimal.ZERO;
        }
    }
}
