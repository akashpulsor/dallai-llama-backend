package com.dalai.llama.tenant.service;


import com.dalai.llama.tenant.domain.entity.Tenant;

import java.util.UUID;

public interface KeycloakRealmService {

    void createRealm(String realm, String displayName);

    void createRoles(String realm);

    void createClient(String realm, String clientId);

    void deleteTenant(String slug);
    void createAdminUser(Tenant tenant, String realmName, String email, String tempPassword);


    void linkUserToUUID(String realmName,String keycloakUserId, UUID tenantId);
}
