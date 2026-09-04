package com.dalai.llama.postprod.service.llmgateway;

import com.dalai.llama.postprod.service.PostProductionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * llm-gateway is the ONLY place any provider call is made from -- same principle as
 * video-generation-service's own LlmGatewayClient. Voice-clone and lip-sync both dispatch through
 * this one {@code /v1/chat} call, differing only by model_id + params; neither this service nor
 * llm-gateway needs a separate code path per capability, that's the whole point of routing
 * everything through model_master rows behind the fal.ai meta-provider adapter.
 */
@Component
public class LlmGatewayClient {

    private final WebClient webClient;
    private final int timeoutMs;

    public LlmGatewayClient(
            WebClient.Builder webClientBuilder,
            @Value("${post-production.llm-gateway.base-url}") String baseUrl,
            @Value("${post-production.llm-gateway.timeout-ms}") int timeoutMs
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
            throw PostProductionException.upstream(
                    "llm-gateway /v1/chat failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        }
    }

    /** Every candidate model registered for a capability (type=lip_sync/tts/voice_clone/foley/
     * music) -- lets a caller see what's actually available to try before picking one via a
     * model override. */
    public List<LlmGatewayModelSummary> listModels(String tenantId, String type) {
        try {
            return webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v1/models").queryParamIfPresent("type", Optional.ofNullable(type)).build())
                    .header("X-Tenant-ID", tenantId)
                    .retrieve()
                    .bodyToFlux(LlmGatewayModelSummary.class)
                    .collectList()
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw PostProductionException.upstream(
                    "llm-gateway /v1/models failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()), ex);
        }
    }
}
