package com.dalai.llama.trendintel.service.llmgateway;

import com.dalai.llama.trendintel.service.TrendIntelligenceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/** llm-gateway is the only place any LLM call is made from -- same principle as every other
 * service in this system. This service has exactly one task: ask Gemini for a trend report. */
@Component
public class LlmGatewayClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public LlmGatewayClient(
            WebClient.Builder webClientBuilder,
            @Value("${trend-intelligence.llm-gateway.base-url}") String baseUrl,
            @Value("${trend-intelligence.llm-gateway.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public LlmGatewayChatResponse chat(String tenantId, String idempotencyKey, LlmGatewayChatRequest request) {
        try {
            return webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/chat", tenantId)
                    .header("Idempotency-Key", idempotencyKey)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(LlmGatewayChatResponse.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw TrendIntelligenceException.upstream(
                    "llm-gateway chat failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }
}
