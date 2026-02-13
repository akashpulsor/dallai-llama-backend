package com.dalai.llama.tenant.service.provisioning.steps;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.client.DidwwClient;
import com.dalai.llama.tenant.service.provisioning.ProvisioningStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConfigureDidwwStep implements ProvisioningStep {

    private final DidwwClient didww;

    public String name() { return "CONFIGURE_DIDWW"; }

    public void execute(Tenant tenant, String productCode) {
        //didww.configureTrunk(tenant.getId(), tenant.getSipExternalIp());
    }
}
