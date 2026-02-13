package com.dalai.llama.tenant.service.provisioning.steps;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.KeycloakRealmService;
import com.dalai.llama.tenant.service.provisioning.ProvisioningStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreateKeycloakRealmStep implements ProvisioningStep {

    private final KeycloakRealmService keycloak;

    public String name() { return "CREATE_KEYCLOAK_REALM"; }

    public void execute(Tenant tenant, String productCode) {
        keycloak.createRealm(tenant.getSlug(), tenant.getName());
    }

    public void compensate(Tenant tenant) {
        keycloak.deleteRealm(tenant.getSlug());
    }
}
