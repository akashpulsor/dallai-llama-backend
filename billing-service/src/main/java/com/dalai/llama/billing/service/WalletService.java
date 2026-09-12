package com.dalai.llama.billing.service;

import com.dalai.llama.billing.domain.entity.Wallet;
import com.dalai.llama.billing.domain.entity.enums.TransactionType;

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

    /** For a caller that knows the real charge kind and human-readable detail up front (DID
     * rental, subscription fee, manual adjustment, real per-call usage description) instead of
     * every debit silently landing as USAGE_DEDUCTION with a generic sentence. */
    void debit(UUID tenantId, BigDecimal amount, TransactionType type, String reference,
               UUID subscriptionId, String idempotencyKey, String description, UUID projectId);

    /** Same as above, for credits that aren't a plain wallet recharge (manual credit adjustment,
     * refund). */
    void credit(UUID tenantId, BigDecimal amount, TransactionType type, String reference,
                UUID subscriptionId, String idempotencyKey, String description);
}
