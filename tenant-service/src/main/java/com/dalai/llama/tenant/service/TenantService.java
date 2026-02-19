package com.dalai.llama.tenant.service;



import com.dalai.llama.tenant.dto.request.CreateTenantRequest;
import com.dalai.llama.tenant.dto.request.SubscriptionActiveRequest;
import com.dalai.llama.tenant.dto.request.UpdateTenantRequest;
import com.dalai.llama.tenant.dto.response.*;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

public interface TenantService {

    TenantResponse createTenant(CreateTenantRequest request, Jwt jwt);

    List<TenantResponse> listTenants();

    TenantResponse getTenant(UUID tenantId);

    TenantDetailResponse getTenantDetails(UUID tenantId);

    TenantResponse updateTenant(UUID tenantId, UpdateTenantRequest request);

    void suspendTenant(UUID tenantId, String reason);

    void rejectKyc(UUID tenantId, String reason);

    void activateTenant(UUID tenantId);

    void deleteTenant(UUID tenantId, String reason);

    void triggerProvisioning(UUID tenantId);

    void retryProvisioning(UUID tenantId);

    void approveKyc(UUID tenantId);

    ProvisioningStatusResponse getProvisioningStatus(UUID tenantId);

    ReadinessCheckResponse checkReadiness(UUID tenantId);

    void onSubscriptionActive(UUID tenantId, SubscriptionActiveRequest request);

    // Internal events

    void setIdentity(UUID tenantId);

    void onPlanAssigned(UUID tenantId, UUID planId, String planCode);

    void onDidPurchased(UUID tenantId);

    void onWalletCreated(UUID tenantId, UUID walletId);

    void onWalletFunded(UUID tenantId);

    void onBillingStateChanged(UUID tenantId, String state);

    TenantProvisioningConfigResponse getProvisioningConfig(UUID tenantId);

    void restartProvisioning(UUID tenantId, String reason);
}
