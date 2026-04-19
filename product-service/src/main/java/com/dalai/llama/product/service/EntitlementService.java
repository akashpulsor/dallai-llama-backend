package com.dalai.llama.product.service;



import com.dalai.llama.product.domain.entity.PlanEntitlement;
import com.dalai.llama.product.dto.response.PlanEntitlementResponse;
import com.dalai.llama.product.dto.response.ProductAppsResponse;
import com.dalai.llama.product.dto.response.ProductConfigResponse;

import java.util.UUID;

public interface EntitlementService {

    PlanEntitlement getEffectiveEntitlements(UUID tenantId);

    void validateDidLimit(UUID tenantId);

    void invalidateCache(UUID tenantId);

    ProductAppsResponse getProductApps(String productCode);

    PlanEntitlementResponse getEntitlementsForPlan(String planCode);

    PlanEntitlementResponse getEntitlementsForPlanId(UUID planId);

    PlanEntitlementResponse getEntitlementsForSubscription(UUID subscriptionId);

    ProductConfigResponse getSubscriptionConfig(UUID subscriptionId);
}
