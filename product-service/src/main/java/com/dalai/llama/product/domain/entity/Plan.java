package com.dalai.llama.product.domain.entity;

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
}
