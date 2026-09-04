package com.dalai.llama.billing.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Mirrors llm-gateway's own {@code com.dalai.llama.llmgateway.kafka.BillingEvent} record field
 * for field (published to {@code llm.billing.events} on every real dispatch -- see its own
 * javadoc). No shared module between the two services, so this is a structural copy, not a
 * shared type; Jackson matches by field name regardless. {@code tenantId} stays {@code String}
 * here (not {@code UUID}) because that's the wire type llm-gateway actually serializes -- it
 * carries tenant_id as the raw {@code X-Tenant-ID} header value throughout its own code, never
 * parsing it to UUID itself. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmBillingEvent {

    private UUID eventId;
    private UUID jobId;
    private String tenantId;
    private UUID projectId;
    private String modelId;
    private int inputTokens;
    private int outputTokens;
    private BigDecimal cost;
    private String currency;
    private String status;
    private OffsetDateTime createdAt;
}
