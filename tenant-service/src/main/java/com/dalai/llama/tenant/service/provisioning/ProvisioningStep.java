package com.dalai.llama.tenant.service.provisioning;


import com.dalai.llama.tenant.domain.entity.Tenant;

public interface ProvisioningStep {

    String name();

    void execute(Tenant tenant);

    default void compensate(Tenant tenant) {}
}
