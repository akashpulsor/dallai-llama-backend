package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.exception.KeycloakException;
import com.dalai.llama.tenant.domain.exception.KeycloakUserNotFoundException;
import com.dalai.llama.tenant.service.KeycloakRealmService;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakRealmServiceImpl implements KeycloakRealmService {

    private final Keycloak keycloakAdminClient;

    @Value("${keycloak.admin.url}")
    private String keycloakUrl;

    @Value("${keycloak.platform-realm}")
    private String platformRealm;

    private static final List<String> TENANT_ROLES = Arrays.asList(
            "TENANT_ADMIN",
            "SUPERVISOR",
            "AGENT",
            "REPORTING_VIEWER",
            "QUALITY_ANALYST"
    );

    // ════════════════════════════════════════════════════════════════
    // PLATFORM REALM USER LOOKUP (NEW)
    // ════════════════════════════════════════════════════════════════
    public boolean realmExists(String realmName) {
        try {
            keycloakAdminClient.realm(realmName).toRepresentation();
            return true;
        } catch (jakarta.ws.rs.NotFoundException e) {
            return false;
        }
    }

    @Override
    public UserRepresentation getPlatformUser(String userId) {
        if (userId == null || userId.isBlank()) {
            log.error("JWT missing 'sub' claim — Keycloak realm '{}' misconfigured. " +
                            "Enable 'access.token.include.sub' in realm settings.",
                    platformRealm);
            throw new IllegalStateException(
                    "Authentication token is invalid (missing subject claim). " +
                            "Please contact support.");
        }
        try {
            return keycloakAdminClient
                    .realm(platformRealm)
                    .users()
                    .get(userId)
                    .toRepresentation();
        } catch (NotFoundException e) {
            log.warn("User {} not found in platform realm {}", userId, platformRealm);
            throw new KeycloakUserNotFoundException(
                    "User " + userId + " not found in platform realm " + platformRealm);
        } catch (Exception e) {
            log.error("Failed to fetch user {} from platform realm {}", userId, platformRealm, e);
            throw new KeycloakException("Failed to fetch platform user: " + userId, e);
        }
    }

    @Override
    public Optional<UserRepresentation> findPlatformUserByEmail(String email) {
        try {
            List<UserRepresentation> users = keycloakAdminClient
                    .realm(platformRealm)
                    .users()
                    .searchByEmail(email, true);
            return users.isEmpty() ? Optional.empty() : Optional.of(users.get(0));
        } catch (Exception e) {
            log.error("Failed to search user by email {} in platform realm {}", email, platformRealm, e);
            return Optional.empty();
        }
    }

    @Override
    public String getRealmId(String realmName) {
        try {
            RealmRepresentation rep = keycloakAdminClient.realm(realmName).toRepresentation();
            return rep.getId();
        } catch (Exception e) {
            log.error("Failed to fetch realm ID for {}", realmName, e);
            throw new KeycloakException("Failed to fetch realm ID: " + realmName, e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // REALM CREATION
    // ════════════════════════════════════════════════════════════════

    @Override
    public void createRealm(String realmName, String displayName) {
        log.info("Creating Keycloak realm: {}", realmName);
        try {
            RealmRepresentation realm = new RealmRepresentation();
            realm.setRealm(realmName);
            realm.setDisplayName(displayName);
            realm.setEnabled(true);

            // Token settings
            realm.setAccessTokenLifespan(3600);
            realm.setSsoSessionIdleTimeout(1800);
            realm.setSsoSessionMaxLifespan(36000);
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

            // Setup global scopes immediately after creation
            createClientScopes(realmName);

            log.info("Created Keycloak realm: {}", realmName);
        }
        catch (jakarta.ws.rs.WebApplicationException e) {
            if (e.getResponse().getStatus() == 409) {
                log.info("Realm {} already exists. Skipping creation step.", realmName);
            } else {
                log.error("Keycloak error: {}", e.getResponse().readEntity(String.class));
                throw e;
            }
        }
        catch (Exception e) {
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

            // Make TENANT_ADMIN composite
            RoleRepresentation adminRole = rolesResource.get("TENANT_ADMIN").toRepresentation();
            adminRole.setComposite(true);

            List<RoleRepresentation> composites = TENANT_ROLES.stream()
                    .filter(r -> !r.equals("TENANT_ADMIN"))
                    .map(r -> rolesResource.get(r).toRepresentation())
                    .toList();

            rolesResource.get("TENANT_ADMIN").addComposites(composites);

            log.info("Created {} roles for realm: {}", TENANT_ROLES.size(), realmName);
        }
        catch (jakarta.ws.rs.WebApplicationException e) {
            if (e.getResponse().getStatus() == 409) {
                log.info("Roles already exist in realm {}. Skipping.", realmName);
            } else {
                log.error("Keycloak error: {}", e.getResponse().readEntity(String.class));
                throw e;
            }
        }
        catch (Exception e) {
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
            client.setName(realmName);
            client.setEnabled(true);
            client.setProtocol("openid-connect");
            client.setPublicClient(false);
            client.setServiceAccountsEnabled(true);
            client.setAuthorizationServicesEnabled(true);
            client.setStandardFlowEnabled(true);
            client.setDirectAccessGrantsEnabled(true);
            client.setImplicitFlowEnabled(false);

            client.setRedirectUris(Arrays.asList(
                    "https://" + realmName.replace("tenant-", "") + ".dalaillama.in/*",
                    "http://localhost:3000/*"
            ));
            client.setWebOrigins(Collections.singletonList("+"));

            client.setAttributes(Map.of(
                    "access.token.lifespan", "3600",
                    "client.secret.creation.time", String.valueOf(System.currentTimeMillis() / 1000)
            ));

            // tenant_id mapper
            ProtocolMapperRepresentation tenantMapper = new ProtocolMapperRepresentation();
            tenantMapper.setName("tenant-id-mapper");
            tenantMapper.setProtocol("openid-connect");
            tenantMapper.setProtocolMapper("oidc-usermodel-attribute-mapper");

            Map<String, String> config = new HashMap<>();
            config.put("user.attribute", "tenant_id");
            config.put("claim.name", "tenant_id");
            config.put("jsonType.label", "String");
            config.put("id.token.claim", "true");
            config.put("access.token.claim", "true");
            config.put("userinfo.token.claim", "true");
            tenantMapper.setConfig(config);

            client.setProtocolMappers(Collections.singletonList(tenantMapper));

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

    // ════════════════════════════════════════════════════════════════
    // ADMIN USER CREATION
    // ════════════════════════════════════════════════════════════════

    @Override
    public String createAdminUser(String realmName, String email, String displayName, String temporaryPassword) {
        log.info("Creating admin user {} in realm {}", email, realmName);

        RealmResource realm = keycloakAdminClient.realm(realmName);
        UsersResource users = realm.users();

        List<UserRepresentation> existing = users.searchByEmail(email, true);
        if (!existing.isEmpty()) {
            String existingId = existing.get(0).getId();
            log.info("Admin user {} already exists in realm {} with id {}", email, realmName, existingId);
            assignAdminRole(realm, existingId);
            return existingId;
        }

        UserRepresentation user = new UserRepresentation();
        user.setEnabled(true);
        user.setEmail(email);
        user.setUsername(email);
        user.setEmailVerified(true);

        if (displayName != null && displayName.contains(" ")) {
            String[] parts = displayName.split(" ", 2);
            user.setFirstName(parts[0]);
            user.setLastName(parts[1]);
        } else {
            user.setFirstName(displayName != null ? displayName : "Admin");
        }

        Response response = users.create(user);
        if (response.getStatus() != 201) {
            throw new KeycloakException("Failed to create admin user: HTTP " + response.getStatus());
        }

        String locationHeader = response.getHeaderString("Location");
        String userId = locationHeader.substring(locationHeader.lastIndexOf('/') + 1);
        response.close();

        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(temporaryPassword);
        cred.setTemporary(true);
        users.get(userId).resetPassword(cred);

        assignAdminRole(realm, userId);

        log.info("Created admin user {} in realm {} with id {}", email, realmName, userId);
        return userId;
    }

    @Override
    public void createAdminUser(Tenant tenant, String realmName, String email, String tempPassword) {
        log.info("Creating admin user {} for realm: {}", email, realmName);
        try {
            RealmResource realmResource = keycloakAdminClient.realm(realmName);
            UsersResource usersResource = realmResource.users();

            UserRepresentation user = new UserRepresentation();
            user.setUsername(email);
            user.setEmail(email);
            user.setEmailVerified(true);
            user.setEnabled(true);
            user.setRequiredActions(Collections.singletonList("UPDATE_PASSWORD"));

            Map<String, List<String>> attributes = new HashMap<>();
            attributes.put("tenant_id", Collections.singletonList(tenant.getId().toString()));
            user.setAttributes(attributes);

            Response response = usersResource.create(user);
            if (response.getStatus() != 201) {
                throw new KeycloakException("Failed to create user, status: " + response.getStatus(), null);
            }

            String userId = extractUserIdFromLocation(response);

            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(tempPassword);
            credential.setTemporary(true);
            usersResource.get(userId).resetPassword(credential);

            RoleRepresentation adminRole = realmResource.roles().get("TENANT_ADMIN").toRepresentation();
            usersResource.get(userId).roles().realmLevel().add(Collections.singletonList(adminRole));

            log.info("Created admin user {} for realm: {}", email, realmName);
        } catch (Exception e) {
            log.error("Failed to create admin user {} for realm: {}", email, realmName, e);
            throw new KeycloakException("Failed to create admin user: " + email, e);
        }
    }

    private void assignAdminRole(RealmResource realm, String userId) {
        try {
            RoleRepresentation adminRole = realm.roles().get("TENANT_ADMIN").toRepresentation();
            realm.users().get(userId).roles().realmLevel()
                    .add(Collections.singletonList(adminRole));
        } catch (Exception e) {
            log.warn("Could not assign TENANT_ADMIN role: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // USER ↔ TENANT LINKING
    // ════════════════════════════════════════════════════════════════

    @Override
    public void linkUserToUUID(String realmName, String keycloakUserId, UUID tenantId) {
        UserResource userResource = keycloakAdminClient.realm(realmName)
                .users()
                .get(keycloakUserId);

        UserRepresentation user = userResource.toRepresentation();
        user.singleAttribute("tenant_id", tenantId.toString());
        userResource.update(user);

        log.info("Linked user {} in realm {} to tenant {}", keycloakUserId, realmName, tenantId);
    }

    @Override
    public void removeTenantLinkFromMainUser(String userId, String mainRealmName) {
        log.info("Removing tenant_id attribute from user {} in realm {}", userId, mainRealmName);
        try {
            UserResource userResource = keycloakAdminClient.realm(mainRealmName).users().get(userId);
            UserRepresentation user = userResource.toRepresentation();

            if (user.getAttributes() != null) {
                user.getAttributes().remove("tenant_id");
                userResource.update(user);
                log.info("User {} is now unlinked from the deleted tenant.", userId);
            }
        } catch (Exception e) {
            log.error("Could not remove tenant_id attribute for user {}", userId, e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // DELETION
    // ════════════════════════════════════════════════════════════════

    @Override
    public void deleteAdminUser(String realmName, String userId) {
        log.info("Deleting admin user {} from realm {}", userId, realmName);
        try {
            keycloakAdminClient.realm(realmName).users().get(userId).remove();
            log.info("Deleted admin user {} from realm {}", userId, realmName);
        } catch (NotFoundException e) {
            log.warn("Admin user {} not found in realm {} — may already be deleted", userId, realmName);
        } catch (Exception e) {
            log.error("Failed to delete admin user {} from realm {}: {}", userId, realmName, e.getMessage());
        }
    }

    @Override
    public void deleteTenant(UUID slug) {
        String realmName = "tenant-" + slug;
        log.info("Initiating deletion for realm: {}", realmName);

        try {
            keycloakAdminClient.realm(realmName).remove();
            log.info("Successfully deleted Keycloak realm: {}", realmName);
        } catch (NotFoundException e) {
            log.warn("Realm {} not found; it may have already been deleted.", realmName);
        } catch (Exception e) {
            log.error("Failed to delete Keycloak realm: {}", realmName, e);
            throw new KeycloakException("Could not delete tenant realm: " + realmName, e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // CLIENT SCOPES
    // ════════════════════════════════════════════════════════════════

    @Override
    public void createClientScopes(String realmName) {
        log.info("Configuring client scopes for realm: {}", realmName);
        try {
            RealmResource realmResource = keycloakAdminClient.realm(realmName);

            ProtocolMapperRepresentation tenantMapper = new ProtocolMapperRepresentation();
            tenantMapper.setName("tenant-id-mapper");
            tenantMapper.setProtocol("openid-connect");
            tenantMapper.setProtocolMapper("oidc-usermodel-attribute-mapper");

            Map<String, String> config = new HashMap<>();
            config.put("user.attribute", "tenant_id");
            config.put("claim.name", "tenant_id");
            config.put("jsonType.label", "String");
            config.put("id.token.claim", "true");
            config.put("access.token.claim", "true");
            config.put("userinfo.token.claim", "true");
            tenantMapper.setConfig(config);

            ClientScopeRepresentation tenantScope = new ClientScopeRepresentation();
            tenantScope.setName("tenant-context");
            tenantScope.setDescription("Injects tenant_id into token");
            tenantScope.setProtocol("openid-connect");
            tenantScope.setAttributes(Map.of(
                    "include.in.token.scope", "true",
                    "display.on.consent.screen", "false"));
            tenantScope.setProtocolMappers(Collections.singletonList(tenantMapper));

            Response response = realmResource.clientScopes().create(tenantScope);

            if (response.getStatus() == 201) {
                String createdScopeId = extractIdFromLocation(response);
                realmResource.addDefaultDefaultClientScope(createdScopeId);
                log.info("Added 'tenant-context' as a default client scope for realm: {}", realmName);
            } else if (response.getStatus() == 409) {
                log.info("Client scope already exists.");
            }

        } catch (Exception e) {
            log.error("Failed to create client scopes for realm: {}", realmName, e);
            throw new KeycloakException("Failed to create scopes", e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════

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

    private String extractIdFromLocation(Response response) {
        String location = response.getHeaderString("Location");
        if (location != null) {
            return location.substring(location.lastIndexOf('/') + 1);
        }
        return null;
    }
}