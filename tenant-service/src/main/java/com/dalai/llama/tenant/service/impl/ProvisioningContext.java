package com.dalai.llama.tenant.service.impl;



import com.dalai.llama.tenant.domain.entity.ProvisioningTask;
import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.dto.response.AdminCredentials;
import com.dalai.llama.tenant.dto.response.PlanEntitlementResponse;
import lombok.Builder;
import lombok.Data;

/**
 * Mutable context bag carried through the provisioning pipeline.
 * Avoids redundant DB/API calls between steps.
 */
@Data
@Builder
public class ProvisioningContext {

    private Tenant tenant;
    private TenantApp app;
    private ProvisioningTask task;

    // Resolved at FETCH_ENTITLEMENTS
    private PlanEntitlementResponse entitlements;

    // Resolved at RESOLVE_NAMESPACE
    private String resolvedNamespace;
    private boolean dedicatedInfra;

    // Created at CREATE_ADMIN_USER
    private AdminCredentials adminCredentials;
}