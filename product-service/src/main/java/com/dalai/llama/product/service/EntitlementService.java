package com.dalai.llama.product.service;



import com.dalai.llama.product.domain.entity.PlanEntitlement;

import java.util.UUID;

public interface EntitlementService {

    PlanEntitlement getEffectiveEntitlements(UUID tenantId);

    void validateDidLimit(UUID tenantId);

    void invalidateCache(UUID tenantId);
}
