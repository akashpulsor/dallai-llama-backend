package com.dalai.llama.billing.domain.exception;

import java.util.UUID;

public class WalletNotFoundException extends RuntimeException {

    public WalletNotFoundException(UUID tenantId) {
        super("Wallet not found for tenant: " + tenantId);
    }
}
