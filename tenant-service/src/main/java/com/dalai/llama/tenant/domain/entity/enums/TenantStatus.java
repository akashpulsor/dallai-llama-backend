package com.dalai.llama.tenant.domain.entity.enums;



public enum TenantStatus {

    // Phase 1: Business Setup
    CREATED,
    IDENTITY_CREATED,
    WALLET_CREATED,
    WALLET_DELETED,
    IDENTITY_DELETED,
    INACTIVE,
    PLAN_ASSIGNED,
    PRODUCTS_CONFIGURED,
    DID_PURCHASED,
    KYC_REQUIRED,
    KYC_SUBMITTED,
    SIP_CONFIGURED,
    KYC_APPROVED,
    BILLING_READY,
    READY_TO_PROVISION,
    PROVISIONING_RESTART,
    KYC_REJECTED,
    //PROVISIONING_KEYCLOAK,
    // Phase 2: Technical Provisioning
    PROVISIONING,

    PROVISIONING_NAMESPACE,
    PROVISIONING_INFRA,
    PROVISIONING_TELECOM,
    PROVISIONING_MEDIA_SERVER,
    PROVISIONING_SERVICES,
    PROVISIONING_LOADBALANCER,
    PROVISIONING_WAITING_IP,
    PROVISIONING_DIDWW,
    PROVISIONING_DASHBOARD,
    PROVISIONING_USERS,
    PROVISIONING_AGENT_UI,

    PROVISIONING_LOADBALANCER_DNS,
    HEALTH_CHECK,

    // Final States
    ACTIVE,
    SUSPENDED,
    ERROR,
    DELETED;

    // Add at the end of your existing enum:

    public boolean isProvisioning() {
        return name().startsWith("PROVISIONING") || this == HEALTH_CHECK;
    }

    public boolean isError() {
        return this == ERROR;
    }

    public boolean canReceiveCalls() {
        return this == ACTIVE;
    }
}
