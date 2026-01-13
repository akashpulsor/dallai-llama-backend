package com.dalai.llama.tenant.service;


import java.util.UUID;

public interface ProvisioningOrchestrator {

    void startProvisioning(UUID tenantId);

    void retryProvisioning(UUID tenantId);
}
