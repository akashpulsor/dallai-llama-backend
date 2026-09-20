package com.dalai.llama.llmgateway.kafka;

import com.dalai.llama.llmgateway.dto.ChatResponse;
import com.dalai.llama.llmgateway.service.GatewayException;
import com.dalai.llama.llmgateway.service.LlmGatewayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Async submission side of the "fully-Kafka" LLM job pattern. Reads {@link
 * ChatJobRequestedEvent} off {@code llm.job.requested}, delegates to the existing sync
 * {@link LlmGatewayService#chat} (so provider dispatch, idempotency, wallet-guard, prompt-
 * template rendering, and cost recording all follow the same one code path), and always
 * publishes a terminal {@link ChatJobCompletedEvent} to {@code llm.job.completed} regardless of
 * outcome. Callers own the domain-side follow-up.
 *
 * <p>No thread pool inside the consumer -- Kafka's consumer group is the concurrency mechanism.
 * Scale by adding partitions to {@code llm.job.requested} and (later) replicas of this service.
 * Provider timeouts are enforced inside {@code LlmGatewayService#dispatch}'s existing
 * concurrency-lease + {@code future.get(timeoutMs + ...)} machinery, so an unresponsive Gemini
 * call no longer holds any HTTP thread; it only holds one consumer thread on this pod until it
 * either completes or times out at the provider layer.
 */
@Slf4j
@Component
public class ChatJobRequestedConsumer {

    private final ObjectMapper objectMapper;
    private final LlmGatewayService llmGatewayService;
    private final ChatJobCompletedPublisher completedPublisher;

    public ChatJobRequestedConsumer(
            ObjectMapper objectMapper,
            LlmGatewayService llmGatewayService,
            ChatJobCompletedPublisher completedPublisher
    ) {
        this.objectMapper = objectMapper;
        this.llmGatewayService = llmGatewayService;
        this.completedPublisher = completedPublisher;
    }

    /**
     * One retry, on this path only, when the provider itself fell over.
     *
     * <p>Nobody is waiting here. A synchronous caller holds a connection and has its own deadline,
     * so retrying underneath it only burns provider time it will never collect -- but a job that
     * arrives over Kafka has no such caller, and failing it hands a creator a 503 for something
     * that would very likely have worked on a second attempt. A shot-list generation was lost
     * exactly that way: one attempt, cut off, gone, with the creator told only that the service was
     * unavailable.
     *
     * <p>Retries only 5xx. A 400-class failure -- unknown model, no entitlement, insufficient
     * balance -- is a decision, not an accident, and repeating it wastes a minute to reach the same
     * answer.
     *
     * <p>Safe to call twice with the same idempotency key: a terminal row is re-dispatched on the
     * SAME job_id with an incremented attempt_count, so this leaves one job in the ledger with an
     * honest attempt count rather than two rows racing.
     */
    private ChatResponse chatWithOneRetryOnProviderFailure(ChatJobRequestedEvent event) {
        try {
            return llmGatewayService.chat(event.tenantId(), event.idempotencyKey(), event.request());
        } catch (GatewayException ex) {
            if (!ex.getStatus().is5xxServerError()) {
                throw ex;
            }
            log.warn("Provider failure on chat job tenantId={} idempotencyKey={} status={} -- retrying once: {}",
                    event.tenantId(), event.idempotencyKey(), ex.getStatus(), ex.getMessage());
            return llmGatewayService.chat(event.tenantId(), event.idempotencyKey(), event.request());
        }
    }

    @KafkaListener(
            topics = "${llm-gateway.job-requested-kafka-topic:llm.job.requested}",
            groupId = "${llm-gateway.job-requested-consumer-group:llm-gateway-job-worker}"
    )
    public void onMessage(String payload) {
        ChatJobRequestedEvent event;
        try {
            event = objectMapper.readValue(payload, ChatJobRequestedEvent.class);
        } catch (Exception ex) {
            // Poison message -- we have no jobId/tenantId to correlate a FAILED event back to,
            // so drop it after logging rather than block the partition on redeliveries. The
            // caller will time out its own tracking row via its polling grace period.
            log.error("Dropping unparseable ChatJobRequestedEvent payload: {}", ex.getMessage(), ex);
            return;
        }

        log.info("Processing chat job tenantId={} idempotencyKey={}",
                event.tenantId(), event.idempotencyKey());

        try {
            ChatResponse response = chatWithOneRetryOnProviderFailure(event);
            completedPublisher.publish(ChatJobCompletedEvent.succeeded(
                    response.jobId(), event.tenantId(), event.idempotencyKey(), response));
        } catch (GatewayException ex) {
            // Known, mapped error from the gateway itself (bad model, insufficient balance,
            // provider timeout, etc.) -- surface as FAILED with the actual message, no stack.
            log.warn("Chat job failed tenantId={} idempotencyKey={} status={} message={}",
                    event.tenantId(), event.idempotencyKey(), ex.getStatus(), ex.getMessage());
            completedPublisher.publish(ChatJobCompletedEvent.failed(
                    resolveJobIdForFailure(event), event.tenantId(), event.idempotencyKey(), ex.getMessage()));
        } catch (Exception ex) {
            // Unexpected -- log with stack for triage, still publish FAILED so the caller isn't
            // left polling forever.
            log.error("Chat job errored tenantId={} idempotencyKey={}",
                    event.tenantId(), event.idempotencyKey(), ex);
            completedPublisher.publish(ChatJobCompletedEvent.failed(
                    resolveJobIdForFailure(event), event.tenantId(), event.idempotencyKey(),
                    ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
    }

    /** A GatewayException thrown BEFORE the {@code llm_job} row was claimed (e.g. bad model,
     * insufficient balance) leaves us with no jobId to report. Return {@code null} in that case
     * -- callers correlate by {@code idempotencyKey}, which is always present, so a null jobId
     * still routes the FAILED event back to the right pending job row. */
    private java.util.UUID resolveJobIdForFailure(ChatJobRequestedEvent event) {
        // Intentionally does NOT re-query llm_job here: doing so on the failure path would add a
        // second failure mode (the DB lookup can itself throw) and delay the FAILED event that
        // callers need. Callers correlate by idempotencyKey, not jobId.
        return null;
    }
}
