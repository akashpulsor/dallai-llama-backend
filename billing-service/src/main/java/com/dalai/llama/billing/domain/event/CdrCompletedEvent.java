package com.dalai.llama.billing.domain.event;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class CdrCompletedEvent {
    private UUID tenantId;
    private String callId;
    private String direction;
    private String from;
    private String to;
    private UUID didId;
    private String didNumber;
    private UUID agentId;
    private UUID queueId;
    private UUID ivrFlowId;
    private Instant initiatedAt;
    private Instant answeredAt;
    private Instant endedAt;
    private int durationSeconds;
    private int aiSttSeconds;
    private int aiLlmTokens;
}
