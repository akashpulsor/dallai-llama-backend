package com.dalai.llama.creativeplanning.kafka;

import com.dalai.llama.creativeplanning.service.llmgateway.LlmGatewayChatRequest;

/**
 * Wire-compatible mirror of llm-gateway's {@code ChatJobRequestedEvent}. Same field names and
 * order so llm-gateway's Jackson deserializer accepts our JSON directly -- both sides run with
 * {@code spring.json.add.type.headers: false}, so field-name matching by position is sufficient.
 * <p>Deliberate duplicate of pre-production-service's {@code ChatJobRequestedEvent} rather than a
 * shared module -- the {@code request} field is our own {@link LlmGatewayChatRequest} (also
 * wire-compatible), which is package-local to this service. Extracting a shared kafka module for
 * two mirror classes would trade one duplicated record for a new module to keep in lockstep with
 * llm-gateway's wire contract.
 */
public record ChatJobRequestedEvent(
        String tenantId,
        String idempotencyKey,
        LlmGatewayChatRequest request
) {
}
