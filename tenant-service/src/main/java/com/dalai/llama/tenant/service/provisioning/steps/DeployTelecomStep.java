package com.dalai.llama.tenant.service.provisioning.steps;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.KubernetesProvisioningService;
import com.dalai.llama.tenant.service.provisioning.ProvisioningStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeployTelecomStep implements ProvisioningStep {

    private final KubernetesProvisioningService k8s;

    public String name() { return "DEPLOY_TELECOM"; }

    public void execute(Tenant tenant) {
        k8s.deployTelecom(tenant.getId());
    }
}
