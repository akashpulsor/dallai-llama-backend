package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.exception.KeycloakException;
import com.dalai.llama.tenant.service.KeycloakRealmService;
import com.dalai.llama.tenant.util.PasswordGenerator;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakRealmServiceImpl implements KeycloakRealmService {

    private final Keycloak keycloakAdminClient;

    @Value("${keycloak.admin.url}")
    private String keycloakUrl;

    // Roles to create for each tenant realm
    private static final List<String> TENANT_ROLES = Arrays.asList(
            "TENANT_ADMIN",
            "SUPERVISOR",
            "AGENT",
            "REPORTING_VIEWER",
            "QUALITY_ANALYST"
    );

    @Override
    public void createRealm(String realmName, String displayName) {
        log.info("Creating Keycloak realm: {}", realmName);
        try {
            RealmRepresentation realm = new RealmRepresentation();
            realm.setRealm(realmName);
            realm.setDisplayName(displayName);
            realm.setEnabled(true);

            // Token settings
            realm.setAccessTokenLifespan(3600); // 1 hour
            realm.setSsoSessionIdleTimeout(1800); // 30 minutes
            realm.setSsoSessionMaxLifespan(36000); // 10 hours
            realm.setRefreshTokenMaxReuse(3);

            // Login settings
            realm.setRegistrationAllowed(false);
            realm.setResetPasswordAllowed(true);
            realm.setRememberMe(true);
            realm.setLoginWithEmailAllowed(true);
            realm.setDuplicateEmailsAllowed(false);

            // Password policy
            realm.setPasswordPolicy("length(8) and upperCase(1) and lowerCase(1) and digits(1)");

            // Brute force protection
            realm.setBruteForceProtected(true);
            realm.setMaxFailureWaitSeconds(900);
            realm.setMinimumQuickLoginWaitSeconds(60);
            realm.setWaitIncrementSeconds(60);
            realm.setMaxDeltaTimeSeconds(43200);
            realm.setFailureFactor(5);

            keycloakAdminClient.realms().create(realm);
            log.info("Created Keycloak realm: {}", realmName);
        } catch (Exception e) {
            log.error("Failed to create Keycloak realm: {}", realmName, e);
            throw new KeycloakException("Failed to create realm: " + realmName, e);
        }
    }

    @Override
    public void createRoles(String realmName) {
        log.info("Creating roles for realm: {}", realmName);
        try {
            RealmResource realmResource = keycloakAdminClient.realm(realmName);
            RolesResource rolesResource = realmResource.roles();

            for (String roleName : TENANT_ROLES) {
                RoleRepresentation role = new RoleRepresentation();
                role.setName(roleName);
                role.setDescription(getDescriptionForRole(roleName));
                role.setComposite(false);

                rolesResource.create(role);
                log.debug("Created role: {} in realm: {}", roleName, realmName);
            }

            // Make TENANT_ADMIN a composite role that includes all others
            RoleRepresentation adminRole = rolesResource.get("TENANT_ADMIN").toRepresentation();
            adminRole.setComposite(true);

            List<RoleRepresentation> composites = TENANT_ROLES.stream()
                    .filter(r -> !r.equals("TENANT_ADMIN"))
                    .map(r -> rolesResource.get(r).toRepresentation())
                    .toList();

            rolesResource.get("TENANT_ADMIN").addComposites(composites);

            log.info("Created {} roles for realm: {}", TENANT_ROLES.size(), realmName);
        } catch (Exception e) {
            log.error("Failed to create roles for realm: {}", realmName, e);
            throw new KeycloakException("Failed to create roles for realm: " + realmName, e);
        }
    }

    @Override
    public void createClient(String realmName, String clientId) {
        log.info("Creating OAuth2 client {} for realm: {}", clientId, realmName);
        try {
            RealmResource realmResource = keycloakAdminClient.realm(realmName);

            ClientRepresentation client = new ClientRepresentation();
            client.setClientId(clientId);
            client.setName("Dalai LLAMA - " + realmName);
            client.setEnabled(true);
            client.setProtocol("openid-connect");
            client.setPublicClient(false); // Confidential client
            client.setServiceAccountsEnabled(true);
            client.setAuthorizationServicesEnabled(true);
            client.setStandardFlowEnabled(true); // Authorization code flow
            client.setDirectAccessGrantsEnabled(true); // Resource owner password grant
            client.setImplicitFlowEnabled(false);

            // Redirect URIs
            client.setRedirectUris(Arrays.asList(
                    "https://" + realmName.replace("tenant-", "") + ".dalaillama.in/*",
                    "http://localhost:3000/*"
            ));

            // Web origins
            client.setWebOrigins(Collections.singletonList("+"));

            // Token settings
            client.setAttributes(Map.of(
                    "access.token.lifespan", "3600",
                    "client.secret.creation.time", String.valueOf(System.currentTimeMillis() / 1000)
            ));

            Response response = realmResource.clients().create(client);
            if (response.getStatus() != 201) {
                throw new KeycloakException("Failed to create client, status: " + response.getStatus(), null);
            }

            log.info("Created OAuth2 client {} for realm: {}", clientId, realmName);
        } catch (Exception e) {
            log.error("Failed to create client {} for realm: {}", clientId, realmName, e);
            throw new KeycloakException("Failed to create client: " + clientId, e);
        }
    }

    @Override
    public void createAdminUser(String realmName, String email, String tempPassword) {
        log.info("Creating admin user {} for realm: {}", email, realmName);
        try {
            RealmResource realmResource = keycloakAdminClient.realm(realmName);
            UsersResource usersResource = realmResource.users();

            // Create user
            UserRepresentation user = new UserRepresentation();
            user.setUsername(email);
            user.setEmail(email);
            user.setEmailVerified(true);
            user.setEnabled(true);
            user.setRequiredActions(Collections.singletonList("UPDATE_PASSWORD"));

            Response response = usersResource.create(user);
            if (response.getStatus() != 201) {
                throw new KeycloakException("Failed to create user, status: " + response.getStatus(), null);
            }

            // Get user ID from location header
            String userId = extractUserIdFromLocation(response);

            // Set temporary password
            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(tempPassword);
            credential.setTemporary(true);

            usersResource.get(userId).resetPassword(credential);

            // Assign TENANT_ADMIN role
            RoleRepresentation adminRole = realmResource.roles().get("TENANT_ADMIN").toRepresentation();
            usersResource.get(userId).roles().realmLevel().add(Collections.singletonList(adminRole));

            log.info("Created admin user {} for realm: {}", email, realmName);
        } catch (Exception e) {
            log.error("Failed to create admin user {} for realm: {}", email, realmName, e);
            throw new KeycloakException("Failed to create admin user: " + email, e);
        }
    }

    @Override
    public void deleteRealm(String realmName) {
        log.info("Deleting Keycloak realm: {}", realmName);
        try {
            keycloakAdminClient.realm(realmName).remove();
            log.info("Deleted Keycloak realm: {}", realmName);
        } catch (Exception e) {
            log.error("Failed to delete Keycloak realm: {}", realmName, e);
            // Don't throw - this is for compensation and we want to continue cleanup
        }
    }

    // ========== Private Helpers ==========

    private String getDescriptionForRole(String roleName) {
        return switch (roleName) {
            case "TENANT_ADMIN" -> "Full access to all tenant features and settings";
            case "SUPERVISOR" -> "Monitor agents, listen to calls, access reports";
            case "AGENT" -> "Make and receive calls, view own statistics";
            case "REPORTING_VIEWER" -> "View-only access to dashboards and reports";
            case "QUALITY_ANALYST" -> "Access recordings and add quality scores";
            default -> roleName;
        };
    }

    private String extractUserIdFromLocation(Response response) {
        String location = response.getHeaderString("Location");
        if (location != null) {
            return location.substring(location.lastIndexOf('/') + 1);
        }
        throw new KeycloakException("Could not extract user ID from response", null);
    }
}