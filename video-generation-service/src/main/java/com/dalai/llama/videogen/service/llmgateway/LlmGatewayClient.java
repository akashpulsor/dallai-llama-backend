package com.dalai.llama.videogen.service.llmgateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Every provider call this service ever makes goes through llm-gateway (design doc §2) -- this
 * is the only outbound HTTP client for model calls in this service. Thin wrapper, no retry/rate
 * limiting logic of its own -- llm-gateway already owns idempotency, rate limiting, the wallet
 * guard, and crash-safe job persistence; duplicating any of that here would be wrong, not just
 * redundant.
 */
@Component
public class LlmGatewayClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public LlmGatewayClient(
            WebClient.Builder webClientBuilder,
            @Value("${video-gen.llm-gateway.base-url}") String baseUrl,
            @Value("${video-gen.llm-gateway.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public LlmGatewayChatResponse chat(String tenantId, String idempotencyKey, LlmGatewayChatRequest request) {
        try {
            return webClient.post()
                    .uri("/v1/chat")
                    .header("X-Tenant-ID", tenantId)
                    .header("Idempotency-Key", idempotencyKey)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(LlmGatewayChatResponse.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw new LlmGatewayCallException(
                    "llm-gateway /v1/chat failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        }
    }

    public LlmGatewayEstimateResponse estimate(String tenantId, LlmGatewayChatRequest request) {
        try {
            return webClient.post()
                    .uri("/v1/estimate")
                    .header("X-Tenant-ID", tenantId)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(LlmGatewayEstimateResponse.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw new LlmGatewayCallException(
                    "llm-gateway /v1/estimate failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        }
    }

    public LlmGatewayJobStatusResponse cancel(String tenantId, UUID llmGatewayJobId) {
        try {
            return webClient.post()
                    .uri("/v1/jobs/{jobId}/cancel", llmGatewayJobId)
                    .header("X-Tenant-ID", tenantId)
                    .retrieve()
                    .bodyToMono(LlmGatewayJobStatusResponse.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw new LlmGatewayCallException(
                    "llm-gateway /v1/jobs/%s/cancel failed status=%s body=%s"
                            .formatted(llmGatewayJobId, ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        }
    }

    public List<LlmGatewayModelSummary> listModels(String tenantId, String type) {
        try {
            return webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v1/models").queryParamIfPresent("type", java.util.Optional.ofNullable(type)).build())
                    .header("X-Tenant-ID", tenantId)
                    .retrieve()
                    .bodyToFlux(LlmGatewayModelSummary.class)
                    .collectList()
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw new LlmGatewayCallException(
                    "llm-gateway /v1/models failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        }
    }

    public static class LlmGatewayCallException extends RuntimeException {
        public LlmGatewayCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
