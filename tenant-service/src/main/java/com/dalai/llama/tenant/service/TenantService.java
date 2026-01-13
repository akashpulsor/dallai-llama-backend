package com.dalai.llama.tenant.service;



import com.dalai.llama.tenant.dto.request.CreateTenantRequest;
import com.dalai.llama.tenant.dto.request.UpdateTenantRequest;
import com.dalai.llama.tenant.dto.response.*;

import java.util.List;
import java.util.UUID;

public interface TenantService {

    TenantResponse createTenant(CreateTenantRequest request);

    List<TenantResponse> listTenants();

    TenantResponse getTenant(UUID tenantId);

    TenantDetailResponse getTenantDetails(UUID tenantId);

    TenantResponse updateTenant(UUID tenantId, UpdateTenantRequest request);

    void suspendTenant(UUID tenantId, String reason);

    void activateTenant(UUID tenantId);

    void deleteTenant(UUID tenantId);

    void triggerProvisioning(UUID tenantId);

    void retryProvisioning(UUID tenantId);

    ProvisioningStatusResponse getProvisioningStatus(UUID tenantId);

    ReadinessCheckResponse checkReadiness(UUID tenantId);

    // Internal events
    void onPlanAssigned(UUID tenantId, UUID planId, String planCode);

    void onDidPurchased(UUID tenantId);

    void onWalletCreated(UUID tenantId, UUID walletId);

    void onWalletFunded(UUID tenantId);

    void onBillingStateChanged(UUID tenantId, String state);

    TenantProvisioningConfigResponse getProvisioningConfig(UUID tenantId);
}
