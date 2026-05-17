package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.UserRole;
import com.dalai.llama.tenant.dto.request.ProvisionTenantUserRequest;
import com.dalai.llama.tenant.dto.response.ProvisionedUserResult;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.service.CredentialDeliveryService;
import com.dalai.llama.tenant.util.PasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent provisioning called by PBX-Core.
 *
 * Tenant-service validates the tenant subscription and owns Keycloak credential
 * creation. PBX-Core still owns the SIP subscriber password.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentProvisionService {

    private final TenantRepository tenantRepository;
    private final TenantAppRepository tenantAppRepository;
    private final CredentialDeliveryService credentialDeliveryService;

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

        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) {
            return denied("tenant_not_found", "Tenant not found: " + tenantId);
        }

        TenantApp app = subscriptionId != null
                ? tenantAppRepository.findBySubscriptionId(UUID.fromString(subscriptionId)).orElse(null)
                : tenantAppRepository.findFirstByTenantId(tenantId).orElse(null);

        if (app == null) {
            return denied("no_subscription", "No active subscription for tenant");
        }

        UserRole userRole = toUserRole(role);
        if (userRole != UserRole.ADMIN) {
            int maxAgents = app.getMaxAgents() != null ? app.getMaxAgents() : 0;
            if (currentAgentCount >= maxAgents) {
                log.warn("Agent limit: tenant={} current={} max={}", tenantId, currentAgentCount, maxAgents);
                return denied("agent_limit_reached",
                        "Agent limit reached: " + currentAgentCount + "/" + maxAgents
                                + ". Upgrade plan for more seats.");
            }
        }

        if (tenant.getKeycloakRealmName() == null || tenant.getKeycloakRealmName().isBlank()) {
            return denied("no_realm", "Tenant Keycloak realm not configured");
        }

        NameParts nameParts = splitName(displayName);
        ProvisionedUserResult provisionedUser = credentialDeliveryService.provisionTenantUser(
                ProvisionTenantUserRequest.builder()
                        .tenantId(tenantId)
                        .primaryRole(userRole)
                        .firstName(nameParts.firstName())
                        .lastName(nameParts.lastName())
                        .email(email)
                        .username(username)
                        .additionalRoles(userRole == UserRole.ADMIN ? java.util.Set.of("TENANT_ADMIN") : null)
                        .createdBy("PBX_CORE")
                        .build());

        return approved(provisionedUser, PasswordGenerator.generate(16));
    }

    private Map<String, Object> approved(ProvisionedUserResult provisionedUser, String sipPassword) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("approved", true);
        result.put("keycloak_user_id", provisionedUser.keycloakUserId());
        result.put("tenant_user_id", provisionedUser.tenantUserId());
        result.put("credential_delivery_id", provisionedUser.credentialDeliveryId());
        result.put("login_url", provisionedUser.loginUrl());
        result.put("sip_password", sipPassword);
        return result;
    }

    private Map<String, Object> denied(String code, String reason) {
        return new LinkedHashMap<>(Map.of("approved", false, "code", code, "reason", reason));
    }

    private UserRole toUserRole(String role) {
        return switch (role == null ? "AGENT" : role.toUpperCase()) {
            case "TENANT_ADMIN", "ADMIN" -> UserRole.ADMIN;
            case "SUPERVISOR" -> UserRole.SUPERVISOR;
            default -> UserRole.AGENT;
        };
    }

    private NameParts splitName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return new NameParts(null, null);
        }
        List<String> parts = List.of(displayName.trim().split("\\s+", 2));
        return new NameParts(parts.get(0), parts.size() > 1 ? parts.get(1) : null);
    }

    private record NameParts(String firstName, String lastName) {}
}
