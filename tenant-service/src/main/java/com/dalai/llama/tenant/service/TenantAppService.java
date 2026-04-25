package com.dalai.llama.tenant.service;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.event.DidPurchasedEvent;
import com.dalai.llama.tenant.domain.event.PlanAssignedEvent;
import com.dalai.llama.tenant.domain.event.SubscriptionActivatedEvent;
import com.dalai.llama.tenant.domain.event.SubscriptionActivationFailedEvent;

import java.util.Optional;
import java.util.UUID;

public interface TenantAppService {

    Optional<TenantApp> getByDid(String did);

    Optional<TenantApp> getByTenantId(UUID tenantId);

    void provisionApp(UUID tenantAppId);

    // ✅ NEW METHODS
    void handlePlanAssigned(PlanAssignedEvent event);

    void handleDidPurchased(DidPurchasedEvent event);

    void handleSubscriptionActivated(SubscriptionActivatedEvent event);

    void handleSubscriptionActivationFailed(SubscriptionActivationFailedEvent event);
}