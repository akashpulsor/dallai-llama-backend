package com.dalai.llama.billing.domain.entity;

import com.dalai.llama.billing.domain.entity.enums.*;
import com.dalai.llama.billing.domain.event.CdrCompletedEvent;
import com.dalai.llama.billing.domain.event.CdrRatedEvent;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cdrs")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cdr {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String callId;

    private String direction;
    private String fromNumber;
    private String toNumber;

    private UUID didId;
    private String didNumber;

    private UUID agentId;
    private UUID queueId;
    private UUID ivrFlowId;

    @Column(nullable = false)
    private Instant initiatedAt;

    private Instant answeredAt;
    private Instant endedAt;

    private int durationSeconds;
    private int billableSeconds;

    @Enumerated(EnumType.STRING)
    private CdrStatus status;

    private boolean rated;
    private UUID ratePlanId;

    @Enumerated(EnumType.STRING)
    private DestinationType destinationType;

    private BigDecimal appliedRate;
    private BigDecimal callCost;

    private int aiSttSeconds;
    private int aiLlmTokens;
    private BigDecimal aiCost;

    private BigDecimal totalCost;

    private boolean processed;
    private Instant processedAt;

    private Instant createdAt;

    /* =========================
       FACTORY: EVENT → CDR
       ========================= */

    public static Cdr fromEvent(Object event) {
        // This assumes a CdrCompletedEvent-like structure
        // Map strictly, do NOT keep event reference

        CdrCompletedEvent e = (CdrCompletedEvent) event;

        return Cdr.builder()
                .id(UUID.randomUUID())
                .tenantId(e.getTenantId())
                .callId(e.getCallId())
                .direction(e.getDirection())
                .fromNumber(e.getFrom())
                .toNumber(e.getTo())
                .didId(e.getDidId())
                .didNumber(e.getDidNumber())
                .agentId(e.getAgentId())
                .queueId(e.getQueueId())
                .ivrFlowId(e.getIvrFlowId())
                .initiatedAt(e.getInitiatedAt())
                .answeredAt(e.getAnsweredAt())
                .endedAt(e.getEndedAt())
                .durationSeconds(e.getDurationSeconds())
                .aiSttSeconds(e.getAiSttSeconds())
                .aiLlmTokens(e.getAiLlmTokens())
                .status(CdrStatus.COMPLETED)
                .rated(false)
                .processed(false)
                .createdAt(Instant.now())
                .build();
    }

    /* =========================
       DOMAIN MUTATION
       ========================= */

    public void markRated(
            UUID ratePlanId,
            DestinationType destinationType,
            int billableSeconds,
            BigDecimal appliedRate,
            BigDecimal callCost,
            BigDecimal aiCost
    ) {
        this.ratePlanId = ratePlanId;
        this.destinationType = destinationType;
        this.billableSeconds = billableSeconds;
        this.appliedRate = appliedRate;
        this.callCost = callCost;
        this.aiCost = aiCost;
        this.totalCost = callCost.add(aiCost);
        this.rated = true;
        this.processed = true;
        this.processedAt = Instant.now();
    }

    /* =========================
       DOMAIN → EVENT
       ========================= */

    public CdrRatedEvent toRatedEvent() {
        return CdrRatedEvent.builder()
                .tenantId(this.tenantId)
                .cdrId(this.id)
                .callCost(this.callCost)
                .aiCost(this.aiCost)
                .totalCost(this.totalCost)
                .occurredAt(Instant.now())
                .build();
    }
}
