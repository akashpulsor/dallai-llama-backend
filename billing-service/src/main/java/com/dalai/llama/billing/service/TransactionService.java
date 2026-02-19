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

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    Optional<Transaction> findByReference(String reference);
}
