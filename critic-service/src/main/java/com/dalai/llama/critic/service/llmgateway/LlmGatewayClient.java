package com.dalai.llama.critic.service.llmgateway;

import com.dalai.llama.critic.service.CriticException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;

/** llm-gateway is the only place any LLM call is made from -- same principle as every other
 * service in this system. Every critic role and the revision planner all dispatch through this
 * one {@code /v1/chat} call, differing only by {@code taskKey} + {@code templateVariables}. */
@Component
public class LlmGatewayClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public LlmGatewayClient(
            WebClient.Builder webClientBuilder,
            @Value("${critic.llm-gateway.base-url}") String baseUrl,
            @Value("${critic.llm-gateway.timeout-ms}") int timeoutMs
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
            throw CriticException.upstream(
                    "llm-gateway chat failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }

    /** Embedding models route through the same {@code /v1/chat} call as everything else -- see
     * llm-gateway's GoogleGeminiProvider, which branches on model type internally. Returns the
     * raw response string (a JSON array of doubles); parse with {@link EmbeddingParser}. */
    public String embed(String tenantId, String embeddingModelId, String text) {
        LlmGatewayChatResponse response = chat(tenantId, "embed-" + java.util.UUID.randomUUID(),
                new LlmGatewayChatRequest(embeddingModelId, java.util.List.of(new LlmGatewayChatRequest.LlmGatewayMessage("user", text)),
                        null, null, null));
        return response == null ? null : response.response();
    }

    /** Empty list if the model has no capability rows registered -- {@code
     * GenerationFeasibilityCritic} treats that as "nothing to assess", not an error. */
    public List<ModelCapabilityView> listModelCapabilities(String tenantId, String modelId) {
        try {
            return webClient.get()
                    .uri("/api/v1/internal/models/{modelId}/capabilities", modelId)
                    .retrieve()
                    .bodyToFlux(ModelCapabilityView.class)
                    .collectList()
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw CriticException.upstream(
                    "llm-gateway models/%s/capabilities failed status=%s body=%s"
                            .formatted(modelId, ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }
}
