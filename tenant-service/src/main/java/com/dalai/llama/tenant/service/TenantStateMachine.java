package com.dalai.llama.tenant.service;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;

public interface TenantStateMachine {

    void transition(Tenant tenant, TenantStatus target, String triggerSource, String message);
}