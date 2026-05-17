package com.dalai.llama.tenant.service;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.event.DidPurchasedEvent;
import com.dalai.llama.tenant.domain.event.PlanAssignedEvent;
import com.dalai.llama.tenant.domain.event.SubscriptionActivatedEvent;
import com.dalai.llama.tenant.domain.event.SubscriptionActivationFailedEvent;
import com.dalai.llama.tenant.dto.response.SubscriptionDetailResponse;

import java.util.Optional;
import java.util.UUID;

public interface TenantAppService {

    Optional<TenantApp> getByDid(String did);

    Optional<TenantApp> getByTenantId(UUID tenantId);

    void provisionApp(UUID tenantAppId);

    void handleSubscriptionActivated(SubscriptionActivatedEvent event, Tenant tenantData);
    // ✅ NEW METHODS
    void handlePlanAssigned(PlanAssignedEvent event);

    void handleDidPurchased(DidPurchasedEvent event);

    SubscriptionDetailResponse getSubscriptionDetails(UUID tenantId, UUID subscriptionId);

    void handleSubscriptionActivationFailed(SubscriptionActivationFailedEvent event);

    void deleteApp(UUID tenantAppId, UUID tenantId);

    void retryProvision(UUID tenantAppId, UUID tenantId);
}