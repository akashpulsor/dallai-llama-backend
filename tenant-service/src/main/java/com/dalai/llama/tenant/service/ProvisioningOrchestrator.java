package com.dalai.llama.tenant.service;


import com.dalai.llama.tenant.domain.entity.Tenant;

import java.util.UUID;

public interface ProvisioningOrchestrator {

    void startProvisioning(UUID tenantId);

    void retryProvisioning(UUID tenantId);

    void setIdentityProvisioner(Tenant tenant);


}
