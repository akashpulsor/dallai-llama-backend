package com.dalai.llama.tenant.service;


public interface KeycloakRealmService {

    void createRealm(String realm, String displayName);

    void createRoles(String realm);

    void createClient(String realm, String clientId);

    void createAdminUser(String realm, String email, String tempPassword);

    void deleteRealm(String realm);
}
