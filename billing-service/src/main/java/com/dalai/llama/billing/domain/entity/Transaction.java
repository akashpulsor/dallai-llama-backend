package com.dalai.llama.billing.domain.entity;

import com.dalai.llama.billing.domain.entity.enums.TransactionType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_txn_tenant", columnList = "tenant_id"),
        @Index(name = "idx_txn_subscription", columnList = "subscription_id"),
        @Index(name = "idx_txn_reference", columnList = "reference")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "subscription_id")
    private UUID subscriptionId;

    // Raw FK passthrough, no project name/lookup here -- null for charges not tied to a project
    // (recharges, DID rental, subscription fees). Mirrors usage_records.project_id (V19).
    @Column(name = "project_id")
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_before", nullable = false, precision = 15, scale = 4)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", nullable = false, precision = 15, scale = 4)
    private BigDecimal balanceAfter;

    private String reference;

    private String description;

    @Column(name = "idempotency_key", unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

