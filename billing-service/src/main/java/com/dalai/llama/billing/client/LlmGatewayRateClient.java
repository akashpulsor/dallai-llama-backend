package com.dalai.llama.billing.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/** billing-service -> llm-gateway, read-only: the rates the gateway charges a model at, which the
 * production-cost estimate prices a video's calls with. */
@Component
public class LlmGatewayRateClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public LlmGatewayRateClient(
            WebClient.Builder webClientBuilder,
            @Value("${billing.llm-gateway.base-url:http://llm-gateway.apps.svc.cluster.local:8080}") String baseUrl,
            @Value("${billing.llm-gateway.timeout-ms:5000}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public List<Rate> current(String modelId) {
        List<Rate> rates = webClient.get()
                .uri(uri -> uri.path("/api/v1/internal/rate-cards").queryParam("modelId", modelId).build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Rate>>() {})
                .block(Duration.ofMillis(timeoutMs));
        return rates == null ? List.of() : rates;
    }

    /** Structural mirror of llm-gateway's {@code RateCardView}. */
    public record Rate(String modelId, String resolution, BigDecimal perSecondCost,
                       BigDecimal inputTokenCost, BigDecimal outputTokenCost, String currency) {}
}
