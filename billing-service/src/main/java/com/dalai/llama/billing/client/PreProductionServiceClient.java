package com.dalai.llama.billing.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.UUID;

/**
 * billing-service -> pre-production-service, for the one thing billing needs to push rather than
 * be asked for: telling pre-production-service a client-review payment (lock or extra-review) was
 * captured, once Razorpay's webhook confirms it independently of whatever the client's own browser
 * request managed to do. See {@code ClientReviewPaymentService#captureFromWebhook} for why this
 * exists -- without it, a client-review verify call that times out on the client-facing request
 * leaves the project permanently unlocked (funding has this same safety net via Kafka; this flow
 * has no Kafka consumer on the pre-production-service side, so a direct internal call is the
 * simpler fix, same shape as every other service-to-service client in this codebase).
 */
@Slf4j
@Component
public class PreProductionServiceClient {

    private record ClientReviewPaymentCapturedRequest(String kind) {}

    private final WebClient webClient;
    private final int timeoutMs;

    public PreProductionServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${billing.pre-production.base-url}") String baseUrl,
            @Value("${billing.pre-production.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public void notifyClientReviewPaymentCaptured(UUID tenantId, UUID projectId, String kind) {
        webClient.post()
                .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/client-review-payment-captured", tenantId, projectId)
                .bodyValue(new ClientReviewPaymentCapturedRequest(kind))
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofMillis(timeoutMs));
    }
}
