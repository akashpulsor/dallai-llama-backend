package com.dalai.llama.llmgateway.kafka;

import com.dalai.llama.llmgateway.dto.ChatRequest;
import com.dalai.llama.llmgateway.dto.ChatResponse;
import com.dalai.llama.llmgateway.service.GatewayException;
import com.dalai.llama.llmgateway.service.LlmGatewayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A creator should not be told "service unavailable" for something that would have worked.
 *
 * <p>A shot-list generation was lost to a single provider timeout: one attempt, cut off at the
 * deadline, no shots, no partial result, and a 503 on the page. Nobody is waiting on this path --
 * the job arrives over Kafka -- so there is room to try again, which a synchronous caller holding
 * a connection does not have.
 */
class ChatJobRetryTest {

    private final LlmGatewayService gateway = mock(LlmGatewayService.class);
    private final ChatJobCompletedPublisher publisher = mock(ChatJobCompletedPublisher.class);
    private final ChatJobRequestedConsumer consumer =
            new ChatJobRequestedConsumer(new ObjectMapper(), gateway, publisher);

    private static final String TENANT = UUID.randomUUID().toString();
    private static final String KEY = "shot-list-generate-" + UUID.randomUUID();

    private String payload() throws Exception {
        return new ObjectMapper().writeValueAsString(
                new ChatJobRequestedEvent(TENANT, KEY, request()));
    }

    private static ChatRequest request() {
        return new ChatRequest("gemini-2.5-flash", null,
                java.util.List.of(new com.dalai.llama.llmgateway.dto.ChatMessage("user", "generate a shot list")),
                java.util.Map.of(), java.util.List.of(), java.util.List.of(), null, java.util.Map.of(), null);
    }

    private static ChatResponse ok() {
        return new ChatResponse(UUID.randomUUID(), "gemini-2.5-flash", "{}", null, 0);
    }

    /** The timeout that actually happened: 503 from the provider, then success. */
    @Test
    void retriesOnceWhenTheProviderFails() throws Exception {
        when(gateway.chat(anyString(), anyString(), any()))
                .thenThrow(new GatewayException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Gemini call failed: Did not observe any item or terminal signal within 90000ms"))
                .thenReturn(ok());

        consumer.onMessage(payload());

        verify(gateway, times(2)).chat(anyString(), anyString(), any());
        verify(publisher).publish(any(ChatJobCompletedEvent.class));
    }

    /**
     * A 4xx is a decision, not an accident. Repeating an unknown model or an unaffordable request
     * spends another provider round trip to arrive at the same answer.
     */
    @Test
    void doesNotRetryARequestThatWasRefused() throws Exception {
        when(gateway.chat(anyString(), anyString(), any()))
                .thenThrow(GatewayException.badRequest("Unknown model_id"));

        consumer.onMessage(payload());

        verify(gateway, times(1)).chat(anyString(), anyString(), any());
    }

    /** Two failures still end as one honest FAILED, never a silent disappearance. */
    @Test
    void reportsFailureWhenBothAttemptsFail() throws Exception {
        when(gateway.chat(anyString(), anyString(), any()))
                .thenThrow(new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "provider down"));

        consumer.onMessage(payload());

        verify(gateway, times(2)).chat(anyString(), anyString(), any());
        verify(publisher).publish(any(ChatJobCompletedEvent.class));
    }
}
