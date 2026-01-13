package com.dalai.llama.billing.service;

import com.dalai.llama.billing.domain.entity.enums.TransactionType;

import java.math.BigDecimal;
import java.util.UUID;

public interface TransactionService {

    void recordTransaction(
            UUID tenantId,
            UUID walletId,
            BigDecimal amount,
            TransactionType type,
            String reference
    );
}
