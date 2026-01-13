package com.dalai.llama.tenant.service.provisioning.steps;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import com.dalai.llama.tenant.service.provisioning.ProvisioningStep;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class FinalizeStep implements ProvisioningStep {

    public String name() { return "FINALIZE"; }

    public void execute(Tenant tenant) {
        tenant.setActivatedAt(OffsetDateTime.now());
        tenant.setStatus(TenantStatus.ACTIVE);
    }
}
