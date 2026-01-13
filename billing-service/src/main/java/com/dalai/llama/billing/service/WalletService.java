package com.dalai.llama.billing.service;

import java.math.BigDecimal;
import java.util.UUID;

public interface WalletService {

    void createWallet(UUID tenantId);

    void deleteWallet(UUID tenantId);

    void credit(UUID tenantId, BigDecimal amount, String reference);

    void debit(UUID tenantId, BigDecimal amount, String reference);

    BigDecimal getBalance(UUID tenantId);
}
