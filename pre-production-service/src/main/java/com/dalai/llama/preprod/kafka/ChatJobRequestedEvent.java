package com.dalai.llama.preprod.kafka;

import com.dalai.llama.preprod.service.llmgateway.LlmGatewayChatRequest;

/**
 * Wire-compatible mirror of llm-gateway's {@code ChatJobRequestedEvent}. Same field names and
 * order so llm-gateway's Jackson deserializer accepts our JSON directly -- both sides run
 * with {@code spring.json.add.type.headers: false}, so no type-info is expected on the wire and
 * matching field names is sufficient.
 *
 * <p>{@code request} is our {@link LlmGatewayChatRequest} which itself is wire-compatible with
 * llm-gateway's {@code ChatRequest} (same convention -- see the existing sync
 * {@link com.dalai.llama.preprod.service.llmgateway.LlmGatewayClient}).
 */
public record ChatJobRequestedEvent(
        String tenantId,
        String idempotencyKey,
        LlmGatewayChatRequest request
) {
}
