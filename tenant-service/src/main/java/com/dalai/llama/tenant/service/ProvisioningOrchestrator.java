package com.dalai.llama.tenant.service;

import java.util.UUID;

public interface ProvisioningOrchestrator {

    /**
     * Starts or resumes provisioning for a TenantApp (async).
     * Idempotent — safe to call on already-completed or in-progress apps.
     */
    void provision(UUID tenantAppId);
}