package com.dalai.llama.billing.service;

import java.util.UUID;

public interface BillingStateService {

    void evaluateState(UUID tenantId);

    void transitionExpiredGraceTenants();

    void detectLowBalanceWallets();
}
