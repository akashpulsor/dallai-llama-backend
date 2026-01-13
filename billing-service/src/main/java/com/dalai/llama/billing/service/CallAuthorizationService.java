package com.dalai.llama.billing.service;

import java.util.UUID;

public interface CallAuthorizationService {

    boolean authorizeCall(UUID tenantId);
}
