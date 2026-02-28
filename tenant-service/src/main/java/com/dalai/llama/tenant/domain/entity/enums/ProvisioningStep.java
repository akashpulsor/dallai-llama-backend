package com.dalai.llama.tenant.domain.entity.enums;


import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ProvisioningStep {

    CREATE_KEYCLOAK_REALM(1, "Create Keycloak Realm", true),
    //CREATE_KEYCLOAK_ROLES(2, "Create Keycloak Roles", true),
    //CREATE_KEYCLOAK_CLIENT(3, "Create OAuth2 Client", true),
    //CREATE_KEYCLOAK_ADMIN(4, "Create Admin User", true),
    FINALIZE(27, "Finalize Provisioning", false);
    private final int order;
    private final String description;
    private final boolean compensatable;
}
