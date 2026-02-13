package com.dalai.llama.tenant.service.provisioning.steps;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.provisioning.ProvisioningStep;
import org.springframework.stereotype.Component;

@Component
public class HealthCheckStep implements ProvisioningStep {

    public String name() { return "HEALTH_CHECK"; }

    public void execute(Tenant tenant, String productCode) {
        // ping services; throw exception if unhealthy
    }
}
