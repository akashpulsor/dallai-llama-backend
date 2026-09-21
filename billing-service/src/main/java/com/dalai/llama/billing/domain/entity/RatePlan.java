package com.dalai.llama.billing.domain.entity;


import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rate_plans")
@Getter @Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RatePlan {

    @Id
    private UUID id;

    @Column(unique = true, nullable = false)
    private String code;

    private String name;
    private String description;

    private boolean isDefault;
    private boolean active;

    /** Recurring charge per cycle in {@link #currency}. Populated in V22. Legacy rows default
     * to 0; the admin activation guard refuses to enable a plan whose amount is still 0. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** MONTHLY / QUARTERLY / YEARLY / WEEKLY -- mirrors recurring_charges.frequency's string
     * so a plan and its recurring charge can be compared with equals(). UI currently only
     * offers MONTHLY per product ask; schema leaves the other cycles addressable when needed. */
    @Column(nullable = false, length = 20)
    private String frequency;

    /** Wallet credit granted to the tenant when the plan cycle charges successfully. Kept
     * separate from {@link #amount} so a plan can charge X but credit Y (e.g. charge ₹1000,
     * credit ₹800 to wallet, ₹200 = platform margin). */
    @Column(name = "wallet_credit_per_cycle", nullable = false, precision = 12, scale = 2)
    private BigDecimal walletCreditPerCycle;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;
}
