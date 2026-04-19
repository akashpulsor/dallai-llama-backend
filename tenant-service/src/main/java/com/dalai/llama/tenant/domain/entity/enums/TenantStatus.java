package com.dalai.llama.tenant.domain.entity.enums;



public enum TenantStatus {

    // Phase 1: Business Setup
    CREATED,
    IDENTITY_CREATED,
    WALLET_CREATED,

    // Phase 2: Infrastructure Provisioning
    PROVISIONING,
    PROVISIONING_FAILED,

    // Final States
    ACTIVE,
    SUSPENDED,
    DELETED;

    public boolean isProvisioning() {
        return this == PROVISIONING;
    }

    public boolean isProvisioningFailed() {
        return this == PROVISIONING_FAILED;
    }

    public boolean canReceiveCalls() {
        return this == ACTIVE;
    }
}
