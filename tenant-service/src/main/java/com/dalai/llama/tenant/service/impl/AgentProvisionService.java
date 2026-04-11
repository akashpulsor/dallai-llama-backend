package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.util.PasswordGenerator;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Agent provisioning — called by PBX-Core POST /api/v1/internal/agents/provision.
 *
 * Counting rules:
 *   TENANT_ADMIN → does NOT count against maxAgents (1 free admin per tenant)
 *   AGENT        → counts against maxAgents
 *   SUPERVISOR   → counts against maxAgents (supervisor IS an agent who can monitor)
 *
 * PBX-Core sends current_agent_count (it owns the agents table).
 * We validate against TenantApp.maxAgents here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentProvisionService {

    private final TenantRepository tenantRepository;
    private final TenantAppRepository tenantAppRepository;
    private final Keycloak keycloakAdmin;

    public Map<String, Object> provisionAgent(Map<String, Object> request) {
        UUID tenantId = UUID.fromString((String) request.get("tenant_id"));
        String subscriptionId = (String) request.get("subscription_id");
        String username = (String) request.get("username");
        String email = (String) request.get("email");
        String displayName = (String) request.get("display_name");
        String role = (String) request.getOrDefault("role", "AGENT");
        int currentAgentCount = request.get("current_agent_count") instanceof Number n
                ? n.intValue() : 0;

        log.info("Agent provision: tenant={} user={} role={} current={}",
                tenantId, username, role, currentAgentCount);

        // 1. Find tenant
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) {
            return denied("tenant_not_found", "Tenant not found: " + tenantId);
        }

        // 2. Find TenantApp (has maxAgents from plan entitlements)
        TenantApp app = subscriptionId != null
                ? tenantAppRepository.findBySubscriptionId(UUID.fromString(subscriptionId)).orElse(null)
                : tenantAppRepository.findFirstByTenantId(tenantId).orElse(null);

        if (app == null) {
            return denied("no_subscription", "No active subscription for tenant");
        }

        // 3. Validate entitlement (admin is free, agents/supervisors count)
        if (!"TENANT_ADMIN".equalsIgnoreCase(role)) {
            int maxAgents = app.getMaxAgents() != null ? app.getMaxAgents() : 0;
            if (currentAgentCount >= maxAgents) {
                log.warn("Agent limit: tenant={} current={} max={}", tenantId, currentAgentCount, maxAgents);
                return denied("agent_limit_reached",
                        "Agent limit reached: " + currentAgentCount + "/" + maxAgents
                                + ". Upgrade plan for more seats.");
            }
        }

        // 4. Create Keycloak user
        String realmName = tenant.getKeycloakRealmName();
        if (realmName == null || realmName.isBlank()) {
            return denied("no_realm", "Tenant Keycloak realm not configured");
        }

        String sipPassword = PasswordGenerator.generate(16);

        try {
            RealmResource realm = keycloakAdmin.realm(realmName);
            UsersResource users = realm.users();

            // Check existing by email
            List<UserRepresentation> existing = users.searchByEmail(email, true);
            if (!existing.isEmpty()) {
                String existingId = existing.get(0).getId();
                ensureRole(realm, existingId, mapRole(role));
                log.info("Keycloak user exists: {} ({})", email, existingId);
                return approved(existingId, sipPassword);
            }

            // Create new user
            UserRepresentation user = new UserRepresentation();
            user.setUsername(username);
            user.setEmail(email);
            user.setEmailVerified(true);
            user.setEnabled(true);
            user.setAttributes(Map.of("tenant_id", List.of(tenantId.toString())));

            if (displayName != null && !displayName.isBlank()) {
                String[] parts = displayName.split(" ", 2);
                user.setFirstName(parts[0]);
                if (parts.length > 1) user.setLastName(parts[1]);
            }

            CredentialRepresentation cred = new CredentialRepresentation();
            cred.setType(CredentialRepresentation.PASSWORD);
            cred.setValue(email);
            cred.setTemporary(true);
            user.setCredentials(List.of(cred));
            user.setRequiredActions(List.of("UPDATE_PASSWORD"));

            Response response = users.create(user);
            if (response.getStatus() != 201) {
                String body = response.readEntity(String.class);
                log.error("Keycloak create failed: {} — {}", response.getStatus(), body);
                return denied("keycloak_error", "Keycloak user creation failed: " + body);
            }

            String keycloakUserId = extractUserId(response);
            ensureRole(realm, keycloakUserId, mapRole(role));

            log.info("Created Keycloak user: {} ({}) realm={} role={}",
                    email, keycloakUserId, realmName, role);
            return approved(keycloakUserId, sipPassword);

        } catch (Exception e) {
            log.error("Keycloak error for {}: {}", email, e.getMessage(), e);
            return denied("keycloak_error", "Keycloak error: " + e.getMessage());
        }
    }

    private Map<String, Object> approved(String keycloakUserId, String sipPassword) {
        return new LinkedHashMap<>(Map.of(
                "approved", true,
                "keycloak_user_id", keycloakUserId,
                "sip_password", sipPassword
        ));
    }

    private Map<String, Object> denied(String code, String reason) {
        return new LinkedHashMap<>(Map.of("approved", false, "code", code, "reason", reason));
    }

    private String mapRole(String role) {
        return switch (role.toUpperCase()) {
            case "TENANT_ADMIN", "ADMIN" -> "TENANT_ADMIN";
            case "SUPERVISOR" -> "SUPERVISOR";
            default -> "AGENT";
        };
    }

    private void ensureRole(RealmResource realm, String userId, String roleName) {
        try {
            RoleRepresentation r = realm.roles().get(roleName).toRepresentation();
            realm.users().get(userId).roles().realmLevel().add(List.of(r));
        } catch (Exception e) {
            log.warn("Could not assign role {} to {}: {}", roleName, userId, e.getMessage());
        }
    }

    private String extractUserId(Response response) {
        String loc = response.getHeaderString("Location");
        if (loc != null) return loc.substring(loc.lastIndexOf('/') + 1);
        throw new RuntimeException("Could not extract user ID from Keycloak response");
    }
}