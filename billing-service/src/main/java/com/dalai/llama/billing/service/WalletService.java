package com.dalai.llama.billing.service;

import com.dalai.llama.billing.domain.entity.Wallet;

import java.math.BigDecimal;
import java.util.UUID;

public interface WalletService {

    Wallet createWallet(UUID tenantId);

    Wallet getOrCreateWallet(UUID tenantId);

    void deleteWallet(UUID tenantId);

    void credit(UUID tenantId, BigDecimal amount, String reference);

    void debit(UUID tenantId, BigDecimal amount, String reference, UUID subscriptionId);

    BigDecimal getBalance(UUID tenantId);

    void credit(UUID tenantId, BigDecimal amount, String reference,
                UUID subscriptionId, String idempotencyKey);

    void debit(UUID tenantId, BigDecimal amount, String reference,
               UUID subscriptionId, String idempotencyKey);

    void debit(UUID tenantId, BigDecimal amount, String reference);
}
