package com.dalai.llama.preprod.service.chat;

import com.dalai.llama.preprod.service.PreProductionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * pre-production-service's calls into chat-service: ingest a locked project's content as
 * embeddings, then drive a chat session on the client's behalf (the client has no chat-service
 * JWT -- this service is the trusted, already-tenant-scoped caller, same internal-path convention
 * as every other cross-service call in this codebase). Ingestion is best-effort (mirrors creative-
 * planning-service's own ChatServiceClient exactly: log and move on if chat-service is down,
 * never fail the lock itself over it); session/message calls are NOT best-effort -- those ARE the
 * feature the client is actively using, a silent failure there would just look broken.
 */
@Slf4j
@Component
public class ChatServiceClient {

    public record IngestDocumentRequest(String sourceService, String sourceId, String kind, UUID scopeId, String content) {
    }

    public record CreateChatSessionRequest(String scopeType, UUID scopeId, String title) {
    }

    public record ChatSessionView(UUID id) {
    }

    public record SendChatMessageRequest(String content) {
    }

    public record ChatMessageView(String role, String content, String actionType, String actionStatus) {
    }

    private final WebClient webClient;
    private final int timeoutMs;

    public ChatServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${pre-production.chat.base-url}") String baseUrl,
            @Value("${pre-production.chat.timeout-ms}") int timeoutMs
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.timeoutMs = timeoutMs;
    }

    public void ingest(UUID tenantId, String sourceId, String kind, UUID scopeId, String content) {
        try {
            webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/embedded-documents", tenantId)
                    .bodyValue(new IngestDocumentRequest("pre-production-service", sourceId, kind, scopeId, content))
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofMillis(timeoutMs));
        } catch (Exception ex) {
            // Best-effort, same convention creative-planning-service's ChatServiceClient already
            // established -- a locked project's embeddings failing to save must never fail the
            // lock itself. Logged (unlike a truly silent catch) since this was previously the only
            // way a shot/script/screenplay could go missing from review chat with zero trace --
            // confirmed live: a client asking about a specific shot got no answer, and there was
            // no way to tell whether that shot was never ingested or just didn't rank in retrieval.
            log.warn("chat-service ingestion failed for tenant={} kind={} sourceId={} scopeId={}: {}",
                    tenantId, kind, sourceId, scopeId, ex.getMessage());
        }
    }

    public UUID createSession(UUID tenantId, UUID projectId) {
        try {
            ChatSessionView session = webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/chat-sessions", tenantId)
                    .bodyValue(new CreateChatSessionRequest("PRE_PRODUCTION_PROJECT", projectId, "Client review chat"))
                    .retrieve()
                    .bodyToMono(ChatSessionView.class)
                    .block(Duration.ofMillis(timeoutMs));
            if (session == null) {
                throw PreProductionException.upstream("chat-service returned no session");
            }
            return session.id();
        } catch (WebClientResponseException ex) {
            throw PreProductionException.upstream(
                    "chat-service create-session failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }

    public ChatMessageView sendMessage(UUID tenantId, UUID sessionId, String content) {
        try {
            return webClient.post()
                    .uri("/api/v1/internal/tenants/{tenantId}/chat-sessions/{sessionId}/messages", tenantId, sessionId)
                    .bodyValue(new SendChatMessageRequest(content))
                    .retrieve()
                    .bodyToMono(ChatMessageView.class)
                    .block(Duration.ofMillis(timeoutMs));
        } catch (WebClientResponseException ex) {
            throw PreProductionException.upstream(
                    "chat-service send-message failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }

    public List<ChatMessageView> history(UUID tenantId, UUID sessionId) {
        try {
            ChatMessageView[] messages = webClient.get()
                    .uri("/api/v1/internal/tenants/{tenantId}/chat-sessions/{sessionId}/messages", tenantId, sessionId)
                    .retrieve()
                    .bodyToMono(ChatMessageView[].class)
                    .block(Duration.ofMillis(timeoutMs));
            return messages == null ? List.of() : List.of(messages);
        } catch (WebClientResponseException ex) {
            throw PreProductionException.upstream(
                    "chat-service history failed status=%s body=%s".formatted(ex.getStatusCode(), ex.getResponseBodyAsString()));
        }
    }
}
