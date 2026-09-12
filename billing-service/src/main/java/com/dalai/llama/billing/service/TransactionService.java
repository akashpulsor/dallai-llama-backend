package com.dalai.llama.billing.service;

import com.dalai.llama.billing.domain.entity.Transaction;
import com.dalai.llama.billing.domain.entity.enums.TransactionType;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface TransactionService {

    void recordTransaction(UUID tenantId, UUID walletId, BigDecimal amount,
                           TransactionType type, String reference, UUID subscriptionId);

    void recordTransaction(UUID tenantId, UUID walletId, BigDecimal amount,
                           TransactionType type, String reference, UUID subscriptionId,
                           String idempotencyKey);

    /** Same as the other overloads, but for a caller that already knows the real, human-readable
     * detail for this charge (e.g. "LLM usage: gemini-2.5-flash", a DID number, an adjustment
     * reason) instead of leaving the ledger to fabricate a generic sentence from {@code reference}
     * alone. {@code description}/{@code projectId} may be null -- falls back to the generic
     * per-type text and a null project, same as the other overloads. */
    void recordTransaction(UUID tenantId, UUID walletId, BigDecimal amount,
                           TransactionType type, String reference, UUID subscriptionId,
                           String idempotencyKey, String description, UUID projectId);

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    Optional<Transaction> findByReference(String reference);
}
