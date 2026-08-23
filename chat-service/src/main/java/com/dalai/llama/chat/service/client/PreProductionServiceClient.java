package com.dalai.llama.chat.service.client;

import com.dalai.llama.chat.service.ChatException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class PreProductionServiceClient {

    public record SuggestChangeRequest(String targetType, String targetRef, String note) {
    }

    private final WebClient webClient;
    private final int timeoutMs;

    /** Reference data (what target types exist) barely ever changes -- one process-lifetime cache
     * avoids a round trip on every single chat turn while still refreshing on a restart. Not a
     * hardcoded copy: this IS pre-production-service's own master table, just fetched once. */
    private final AtomicReference<List<SuggestionTargetTypeView>> targetTypeCache = new AtomicReference<>();

    public PreProductionServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${chat.pre-production.base-url}") String baseUrl,
            @Value("${chat.pre-production.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public List<SuggestionTargetTypeView> listSuggestionTargetTypes() {
        List<SuggestionTargetTypeView> cached = targetTypeCache.get();
        if (cached != null) {
            return cached;
        }
        try {
            List<SuggestionTargetTypeView> fetched = webClient.get()
                    .uri("/api/v1/internal/suggestion-target-types")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<SuggestionTargetTypeView>>() {
                    })
                    .block(Duration.ofMillis(timeoutMs));
            List<SuggestionTargetTypeView> result = fetched == null ? List.of() : fetched;
            targetTypeCache.set(result);
            return result;
        } catch (Exception ex) {
            // Best-effort: an empty catalog just means chat won't offer this action this turn,
            // never a hard failure of the chat turn itself.
            return List.of();
        }
    }

    public void suggestChange(String tenantId, UUID projectId, SuggestChangeRequest request) {
        try {
            webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/change-requests", tenantId, projectId)
                    .bodyValue(request)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw ChatException.upstream(
                    "pre-production-service change-request failed status=%s body=%s"
                            .formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }
}
