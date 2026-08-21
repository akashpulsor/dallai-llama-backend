package com.dalai.llama.preprod.service.llmgateway;

import com.dalai.llama.preprod.service.PreProductionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/** llm-gateway is the only place any LLM call is made from -- same principle as every other
 * service in this system. Script/screenplay/shot-list generation all dispatch through this one
 * {@code /v1/chat} call, differing only by {@code taskKey} + {@code templateVariables}. */
@Component
public class LlmGatewayClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public LlmGatewayClient(
            WebClient.Builder webClientBuilder,
            @Value("${pre-production.llm-gateway.base-url}") String baseUrl,
            @Value("${pre-production.llm-gateway.timeout-ms}") int timeoutMs
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
            throw PreProductionException.upstream(
                    "llm-gateway chat failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }
}
