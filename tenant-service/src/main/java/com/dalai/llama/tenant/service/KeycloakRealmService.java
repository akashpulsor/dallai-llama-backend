package com.dalai.llama.tenant.service;

import com.dalai.llama.tenant.domain.entity.Tenant;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.Optional;
import java.util.UUID;

public interface KeycloakRealmService {

    void createRealm(String realmName, String displayName);

    void createRoles(String realmName);

    void createClient(String realmName, String clientId);

    String createAdminUser(String realmName, String email, String displayName, String temporaryPassword);

    void createAdminUser(Tenant tenant, String realmName, String email, String tempPassword);

    void deleteTenant(String slug);

    void linkUserToUUID(String realmName, String keycloakUserId, UUID tenantId);

    void removeTenantLinkFromMainUser(String userId, String mainRealmName);

    void createClientScopes(String realmName);

    // ── NEW METHODS ──

    /** Fetch a user from the platform (dalai-llama) realm by ID. Throws if not found. */
    UserRepresentation getPlatformUser(String userId);

    /** Find a user in the platform realm by email (exact match). */
    Optional<UserRepresentation> findPlatformUserByEmail(String email);

    /** Get the internal UUID of a created realm (different from realm name). */
    String getRealmId(String realmName);
}