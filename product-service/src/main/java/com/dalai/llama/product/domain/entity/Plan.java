package com.dalai.llama.product.domain.entity;

import com.dalai.llama.product.domain.entity.enums.AiStackType;
import com.dalai.llama.product.domain.entity.enums.PlanTier;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "plans")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Plan {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(unique = true)
    private String code;

    private String name;
    private String description;

    @Enumerated(EnumType.STRING)
    private PlanTier tier;

    private BigDecimal monthlyPrice;
    private String currency;

    private boolean isDefault;
    private boolean active;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;

    // Add these fields to your Plan entity class:

    @Column(name = "per_agent_fee", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal perAgentFee = BigDecimal.ZERO;

    @Column(name = "setup_fee", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal setupFee = BigDecimal.ZERO;

    @Column(name = "included_minutes")
    @Builder.Default
    private Integer includedMinutes = 0;

    @Column(name = "included_agents")
    @Builder.Default
    private Integer includedAgents = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_stack_type", length = 20)
    private AiStackType aiStackType;

    @Column(name = "ai_rate_per_min", precision = 6, scale = 2)
    private BigDecimal aiRatePerMin;

    // Relationship to AI config
    @OneToOne(mappedBy = "plan", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private PlanAiConfig aiConfig;
}
