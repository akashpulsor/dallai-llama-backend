package com.dalai.llama.billing.domain.entity;

import com.dalai.llama.billing.domain.entity.enums.BillingUnit;
import com.dalai.llama.billing.domain.entity.enums.DestinationType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rate_cards")
@Getter @Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateCard {

    @Id
    private UUID id;

    private UUID ratePlanId;

    private String metric;
    private String destinationPrefix;

    @Enumerated(EnumType.STRING)
    private DestinationType destinationType;

    private BigDecimal ratePerUnit;

    @Enumerated(EnumType.STRING)
    private BillingUnit unit;

    private int billingIncrement;
    private int minimumCharge;

    private Instant effectiveFrom;
    private Instant effectiveTo;
    private int priority;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;
}
