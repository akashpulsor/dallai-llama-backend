package com.dalai.llama.tenant.service.provisioning.steps;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.KubernetesProvisioningService;
import com.dalai.llama.tenant.service.provisioning.ProvisioningStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreateLoadBalancerStep implements ProvisioningStep {

    private final KubernetesProvisioningService k8s;
    private final LoadBalancerProvisioner loadBalancerProvisioner;

    public String name() { return "CREATE_LOADBALANCER"; }

    public void execute(Tenant tenant, String productCode) {

        k8s.waitForExternalIp(tenant.getId());
        loadBalancerProvisioner.execute(tenant, productCode);
    }
}
