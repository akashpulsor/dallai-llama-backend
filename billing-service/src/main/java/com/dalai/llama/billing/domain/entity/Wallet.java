package com.dalai.llama.billing.domain.entity;

import com.dalai.llama.billing.domain.event.WalletLowBalanceEvent;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallets")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Wallet {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID tenantId;

    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    private String currency;

    private BigDecimal creditLimit;
    private BigDecimal lowBalanceThreshold;

    private Boolean autoRechargeEnabled;
    private BigDecimal autoRechargeThreshold;
    private BigDecimal autoRechargeAmount;
    private UUID defaultPaymentMethodId;

    private Instant lastRechargedAt;
    private Instant lastDeductedAt;

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    /* =======================
       DOMAIN FACTORY
       ======================= */

    public static Wallet createDefault(UUID tenantId) {
        Instant now = Instant.now();
        return Wallet.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .balance(BigDecimal.ZERO)
                .currency("INR")
                .creditLimit(BigDecimal.ZERO)
                .lowBalanceThreshold(BigDecimal.valueOf(500))
                .autoRechargeEnabled(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /* =======================
       DOMAIN BEHAVIOR
       ======================= */

    public void credit(BigDecimal amount) {
        validateAmount(amount);

        this.balance = this.balance.add(amount);
        this.lastRechargedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void debit(BigDecimal amount) {
        validateAmount(amount);

        this.balance = this.balance.subtract(amount);
        this.lastDeductedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public WalletLowBalanceEvent toLowBalanceEvent() {
        return WalletLowBalanceEvent.builder()
                .tenantId(this.tenantId)
                .balance(this.balance)
                .threshold(this.lowBalanceThreshold)
                .occurredAt(Instant.now())
                .build();
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    public boolean hasSufficientBalance(BigDecimal amount) {
        validateAmount(amount);
        return this.balance.compareTo(amount) >= 0;
    }
}
