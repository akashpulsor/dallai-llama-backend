package com.dalai.llama.billing.domain.entity;

import com.dalai.llama.billing.domain.entity.enums.BillingUnit;
import com.dalai.llama.billing.domain.entity.enums.UsageMetric;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_records")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageRecord {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    /** Null for usage not tied to a specific project (e.g. call minutes, subscription fees) --
     * populated for LLM/generation usage sourced from llm-gateway's LlmBillingEvent, which is
     * where the per-project spend cap (VideoPricingService's quoted price vs real accumulated
     * cost) sums this column from, rather than a separate running-total table. */
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UsageMetric metric;

    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BillingUnit unit;

    @Column(precision = 15, scale = 6)
    private BigDecimal unitCost;

    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal totalCost;

    private String sourceType;
    private UUID sourceId;
    private String description;

    private Instant recordedAt;
    private Instant createdAt;

    /* =========================
       DOMAIN FACTORY
       ========================= */

    public static UsageRecord fromCdr(Cdr cdr) {
        Instant now = Instant.now();

        return UsageRecord.builder()
                .id(UUID.randomUUID())
                .tenantId(cdr.getTenantId())
                .metric(resolveMetric(cdr))
                .quantity(resolveQuantity(cdr))
                .unit(resolveUnit(cdr))
                .unitCost(resolveUnitCost(cdr))
                .totalCost(cdr.getTotalCost())
                .sourceType("CDR")
                .sourceId(cdr.getId())
                .description("Call " + cdr.getCallId())
                .recordedAt(cdr.getEndedAt() != null ? cdr.getEndedAt() : now)
                .createdAt(now)
                .build();
    }

    /* =========================
       INTERNAL HELPERS
       ========================= */

    private static UsageMetric resolveMetric(Cdr cdr) {
        return "OUTBOUND".equalsIgnoreCase(cdr.getDirection())
                ? UsageMetric.OUTBOUND_CALL_MINUTES
                : UsageMetric.INBOUND_CALL_MINUTES;
    }

    private static BillingUnit resolveUnit(Cdr cdr) {
        return BillingUnit.MINUTE;
    }

    private static BigDecimal resolveQuantity(Cdr cdr) {
        return BigDecimal.valueOf(cdr.getBillableSeconds())
                .divide(BigDecimal.valueOf(60), 4, BigDecimal.ROUND_HALF_UP);
    }

    private static BigDecimal resolveUnitCost(Cdr cdr) {
        if (cdr.getBillableSeconds() == 0) {
            return BigDecimal.ZERO;
        }
        return cdr.getCallCost()
                .divide(
                        BigDecimal.valueOf(cdr.getBillableSeconds())
                                .divide(BigDecimal.valueOf(60), 4, BigDecimal.ROUND_HALF_UP),
                        6,
                        BigDecimal.ROUND_HALF_UP
                );
    }
}
