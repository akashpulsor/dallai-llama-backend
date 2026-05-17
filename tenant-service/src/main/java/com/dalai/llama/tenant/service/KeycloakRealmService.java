package com.dalai.llama.tenant.service;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.UserRole;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.Optional;
import java.util.UUID;

public interface KeycloakRealmService {

    void createRealm(String realmName, String displayName);

    void createRoles(String realmName);

    void createClient(String realmName, String clientId);

    String createAdminUser(String realmName, String email, String displayName, String temporaryPassword);

    String createTenantUser(String realmName, UUID tenantId, String username, String email,
                            String displayName, String temporaryPassword, UserRole role);

    void createAdminUser(Tenant tenant, String realmName, String email, String tempPassword);

    void deleteTenant(UUID slug);

    void deleteAdminUser(String realmName, String userId);

    void linkUserToUUID(String realmName, String keycloakUserId, UUID tenantId);

    void removeTenantLinkFromMainUser(String userId, String mainRealmName);

    void createClientScopes(String realmName);

    boolean realmExists(String realmName);
    // ── NEW METHODS ──

    /**
     * Grant the master admin user full management rights on a newly-created realm.
     * Must be called after createRealm so the master admin can manage clients,
     * users, roles within the new realm. Idempotent.
     */
    void grantMasterAdminAccessToRealm(String realmName);
    /** Fetch a user from the platform (dalai-llama) realm by ID. Throws if not found. */
    UserRepresentation getPlatformUser(String userId);

    /** Find a user in the platform realm by email (exact match). */
    Optional<UserRepresentation> findPlatformUserByEmail(String email);

    /** Get the internal UUID of a created realm (different from realm name). */
    String getRealmId(String realmName);

    /** Reset a user's password in the given realm. */
    void resetUserPassword(String realmName, String keycloakUserId, String newPassword, boolean temporary);

    /** Disable a user in the given realm. */
    void disableUser(String realmName, String keycloakUserId);

    /** Enable a user in the given realm. */
    void enableUser(String realmName, String keycloakUserId);

    /** Find a user in the given realm by email (exact match). */
    Optional<UserRepresentation> findUserByEmailInRealm(String realmName, String email);
}
