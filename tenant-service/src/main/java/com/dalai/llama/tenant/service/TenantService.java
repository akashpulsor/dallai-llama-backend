package com.dalai.llama.tenant.service;

import com.dalai.llama.tenant.domain.event.WalletCreditedEvent;
import com.dalai.llama.tenant.dto.request.CreateTenantRequest;
import com.dalai.llama.tenant.dto.request.SubscriptionActiveRequest;
import com.dalai.llama.tenant.dto.request.UpdateTenantRequest;
import com.dalai.llama.tenant.dto.response.SubscriptionActiveResponse;
import com.dalai.llama.tenant.dto.response.TenantDetailResponse;
import com.dalai.llama.tenant.dto.response.TenantResponse;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

public interface TenantService {

    TenantResponse createTenant(CreateTenantRequest request, Jwt jwt);

    List<TenantResponse> listTenants();
    TenantResponse getTenant(UUID tenantId);
    TenantDetailResponse getTenantDetails(UUID tenantId);
    TenantResponse updateTenant(UUID tenantId, UpdateTenantRequest request);

    void activateTenant(UUID tenantId);
    void suspendTenant(UUID tenantId, String reason);
    void deleteTenant(UUID tenantId, String reason);

    void onWalletCreated(UUID tenantId, UUID walletId);
    void onWalletFunded(WalletCreditedEvent walletCreditedEvent);
    void onBillingStateChanged(UUID tenantId, String state);

    SubscriptionActiveResponse activateSubscription(SubscriptionActiveRequest request);
}