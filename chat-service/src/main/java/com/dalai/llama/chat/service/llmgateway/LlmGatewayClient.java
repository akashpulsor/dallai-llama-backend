package com.dalai.llama.chat.service.llmgateway;

import com.dalai.llama.chat.service.ChatException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** llm-gateway is the only place any LLM call is made from -- same principle as every other
 * service in this system. chat-service is the one service that needs both text generation (the
 * conversational/action-detection turn) and embeddings (indexing + retrieval). */
@Component
public class LlmGatewayClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public LlmGatewayClient(
            WebClient.Builder webClientBuilder,
            @Value("${chat.llm-gateway.base-url}") String baseUrl,
            @Value("${chat.llm-gateway.timeout-ms}") int timeoutMs
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
            throw ChatException.upstream(
                    "llm-gateway chat failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }

    /** Embedding models route through the same {@code /v1/chat} call -- see llm-gateway's
     * GoogleGeminiProvider, which branches on model type internally. Returns the raw response
     * string (a JSON array of doubles); parse with {@link EmbeddingParser}. */
    public String embed(String tenantId, String embeddingModelId, String text) {
        LlmGatewayChatResponse response = chat(tenantId, "embed-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(embeddingModelId, List.of(new LlmGatewayChatRequest.LlmGatewayMessage("user", text)),
                        null, null, null));
        return response == null ? null : response.response();
    }
}
