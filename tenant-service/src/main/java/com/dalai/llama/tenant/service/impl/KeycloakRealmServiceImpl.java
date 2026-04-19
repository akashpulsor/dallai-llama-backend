package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.exception.KeycloakException;
import com.dalai.llama.tenant.service.KeycloakRealmService;
import com.dalai.llama.tenant.util.PasswordGenerator;
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
            // --- NEW STEP: Setup Global Scopes immediately after creation ---
            createClientScopes(realmName);
            // ---------------------------------------------------------------

            log.info("Created Keycloak realm: {}", realmName);
        }
        catch (jakarta.ws.rs.WebApplicationException e) {
            // Capture the response to check the status code
            if (e.getResponse().getStatus() == 409) {
                log.info("Realm {} already exists. Skipping creation step.", realmName);
                // Do not throw an exception; let the orchestrator move to the next step
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

            // Make TENANT_ADMIN a composite role that includes all others
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
            // Capture the response to check the status code
            if (e.getResponse().getStatus() == 409) {
                log.info("Realm {} already exists. Skipping creation step.", realmName);
                // Do not throw an exception; let the orchestrator move to the next step
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

            // Create the Protocol Mapper to put tenant_id into the JWT
            ProtocolMapperRepresentation tenantMapper = new ProtocolMapperRepresentation();
            tenantMapper.setName("tenant-id-mapper");
            tenantMapper.setProtocol("openid-connect");
            tenantMapper.setProtocolMapper("oidc-usermodel-attribute-mapper");

            Map<String, String> config = new HashMap<>();
            config.put("user.attribute", "tenant_id");      // The attribute we set in createAdminUser
            config.put("claim.name", "tenant_id");          // The key in the JSON Token
            config.put("jsonType.label", "String");
            config.put("id.token.claim", "true");           // Add to ID Token
            config.put("access.token.claim", "true");       // Add to Access Token
            config.put("userinfo.token.claim", "true");

            tenantMapper.setConfig(config);

            // Attach mapper to client
            client.setProtocolMappers(Collections.singletonList(tenantMapper));
            // ----------------------

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

    // ══════════════════════════════════════════════════════════════
// ADD THIS METHOD TO KeycloakRealmServiceImpl.java
// ══════════════════════════════════════════════════════════════

    @Override
    public String createAdminUser(String realmName, String email, String displayName, String temporaryPassword) {
        log.info("Creating admin user {} in realm {}", email, realmName);

        RealmResource realm = keycloakAdminClient.realm(realmName);
        UsersResource users = realm.users();

        // Idempotency: check if user already exists
        List<UserRepresentation> existing = users.searchByEmail(email, true);
        if (!existing.isEmpty()) {
            String existingId = existing.get(0).getId();
            log.info("Admin user {} already exists in realm {} with id {}", email, realmName, existingId);
            // Ensure TENANT_ADMIN role is assigned
            assignAdminRole(realm, existingId);
            return existingId;
        }

        // Create user
        UserRepresentation user = new UserRepresentation();
        user.setEnabled(true);
        user.setEmail(email);
        user.setUsername(email);
        user.setEmailVerified(true);

        // Split display name
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

        // Extract user ID from Location header
        String locationHeader = response.getHeaderString("Location");
        String userId = locationHeader.substring(locationHeader.lastIndexOf('/') + 1);
        response.close();

        // Set temporary password
        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(temporaryPassword);
        cred.setTemporary(true);
        users.get(userId).resetPassword(cred);

        // Assign TENANT_ADMIN role
        assignAdminRole(realm, userId);

        log.info("Created admin user {} in realm {} with id {}", email, realmName, userId);
        return userId;
    }

    private void assignAdminRole(RealmResource realm, String userId) {
        RolesResource rolesResource = realm.roles();
        try {
            RoleRepresentation adminRole = rolesResource.get("TENANT_ADMIN").toRepresentation();
            realm.users().get(userId).roles().realmLevel()
                    .add(Collections.singletonList(adminRole));
        } catch (Exception e) {
            log.warn("Could not assign TENANT_ADMIN role: {}", e.getMessage());
        }
    }

    @Override
    public void createAdminUser(Tenant tenant, String realmName, String email, String tempPassword) {
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
            // --- ADD THIS BLOCK ---
            // Set the tenant_id attribute immediately
            Map<String, List<String>> attributes = new HashMap<>();
            attributes.put("tenant_id", Collections.singletonList(tenant.getId().toString()));
            user.setAttributes(attributes);
            // ----------------------
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


    public void linkUserToUUID(String realmName,String keycloakUserId, UUID tenantId) {
        UserResource userResource = keycloakAdminClient.realm(realmName)
                .users()
                .get(keycloakUserId);

        UserRepresentation user = userResource.toRepresentation();

        // Add or Update the attribute
        user.singleAttribute("tenant_id", tenantId.toString());

        // Push update to Keycloak
        userResource.update(user);
    }

    @Override
    public void deleteTenant(String slug) {
        String realmName = "tenant-" + slug;
        log.info("Initiating deletion for realm: {}", realmName);

        try {
            // This one call deletes the realm, all its clients, roles, and users.
            keycloakAdminClient.realm(realmName).remove();
            log.info("Successfully deleted Keycloak realm: {}", realmName);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.warn("Realm {} not found; it may have already been deleted.", realmName);
        } catch (Exception e) {
            log.error("Failed to delete Keycloak realm: {}", realmName, e);
            throw new KeycloakException("Could not delete tenant realm: " + realmName, e);
        }
    }


    /**
     * Removes the tenant link from the user in the PRIMARY realm.
     */
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
            // We log but don't necessarily fail the whole process if this cleanup fails
            log.error("Could not remove tenant_id attribute for user {}", userId, e);
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

    /**
     * NEW METHOD: Creates a global Client Scope that ensures tenant_id is in ALL tokens.
     */
    public void createClientScopes(String realmName) {
        log.info("Configuring client scopes for realm: {}", realmName);
        try {
            RealmResource realmResource = keycloakAdminClient.realm(realmName);

            // 1. Define the Mapper (moved from createClient)
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

            // 2. Create the Client Scope
            ClientScopeRepresentation tenantScope = new ClientScopeRepresentation();
            tenantScope.setName("tenant-context");
            tenantScope.setDescription("Injects tenant_id into token");
            tenantScope.setProtocol("openid-connect");
            tenantScope.setAttributes(Map.of("include.in.token.scope", "true", "display.on.consent.screen", "false"));
            tenantScope.setProtocolMappers(Collections.singletonList(tenantMapper));

            Response response = realmResource.clientScopes().create(tenantScope);

            if (response.getStatus() == 201) {
                // 3. Make it a Default Scope for the Realm
                // We need the ID of the scope we just created
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

    // Helper to get ID from Response
    private String extractIdFromLocation(Response response) {
        String location = response.getHeaderString("Location");
        if (location != null) {
            return location.substring(location.lastIndexOf('/') + 1);
        }
        return null;
    }
}