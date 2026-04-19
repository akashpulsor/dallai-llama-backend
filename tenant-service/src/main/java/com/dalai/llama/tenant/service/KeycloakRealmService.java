package com.dalai.llama.tenant.service;

import com.dalai.llama.tenant.domain.entity.Tenant;

public interface KeycloakRealmService {

    void createRealm(String realmName, String displayName);

    void createRoles(String realmName);

    void createClient(String realmName, String clientId);

    void createAdminUser(Tenant tenant, String realmName, String email, String tempPassword);

    String createAdminUser(String realmName, String email, String displayName, String temporaryPassword);

    void deleteTenant(String slug);
}