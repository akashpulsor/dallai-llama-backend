package com.dalai.llama.product.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "plan_ai_configs",
        uniqueConstraints = @UniqueConstraint(name = "uk_plan_ai_config", columnNames = "plan_id"),
        indexes = @Index(name = "idx_plan_ai_config_plan", columnList = "plan_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanAiConfig {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stt_provider_id")
    private AiProvider sttProvider;

    @Column(name = "stt_cost_per_min", precision = 8, scale = 4)
    private BigDecimal sttCostPerMin;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tts_provider_id")
    private AiProvider ttsProvider;

    @Column(name = "tts_cost_per_min", precision = 8, scale = 4)
    private BigDecimal ttsCostPerMin;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "llm_provider_id")
    private AiProvider llmProvider;

    @Column(name = "llm_cost_per_min", precision = 8, scale = 4)
    private BigDecimal llmCostPerMin;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public BigDecimal getTotalAiCostPerMin() {
        BigDecimal total = BigDecimal.ZERO;
        if (sttCostPerMin != null) total = total.add(sttCostPerMin);
        if (ttsCostPerMin != null) total = total.add(ttsCostPerMin);
        if (llmCostPerMin != null) total = total.add(llmCostPerMin);
        return total;
    }
}
