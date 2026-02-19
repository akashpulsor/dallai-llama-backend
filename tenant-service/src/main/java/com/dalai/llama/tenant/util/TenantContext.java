package com.dalai.llama.tenant.util;

import lombok.experimental.UtilityClass;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * Extracts tenant context from Keycloak JWT.
 *
 * Keycloak token structure:
 * - sub: user UUID
 * - email: user email
 * - realm_access.roles: user roles
 * - tenant_id: custom claim (added via Keycloak mapper)
 *
 * If tenant_id claim not present, falls back to looking up via user.
 */
@UtilityClass
public class TenantContext {

    private static final String CLAIM_TENANT_ID = "tenant_id";
    private static final String CLAIM_SUB = "sub";

    /**
     * Get tenant ID from JWT.
     * Requires tenant_id custom claim in Keycloak token.
     */
    public static UUID getTenantId(Jwt jwt) {
        // Option 1: Direct claim (if Keycloak mapper configured)
        String tenantId = jwt.getClaimAsString(CLAIM_TENANT_ID);
        if (tenantId != null) {
            return UUID.fromString(tenantId);
        }

        // Option 2: Throw - tenant_id claim is required
        throw new IllegalStateException(
                "Missing tenant_id claim in JWT. Configure Keycloak user attribute mapper.");
    }

    /**
     * Get user ID (sub claim)
     */
    public static UUID getUserId(Jwt jwt) {
        String sub = jwt.getClaimAsString(CLAIM_SUB);
        return UUID.fromString(sub);
    }

    /**
     * Check if user has specific role
     */
    public static boolean hasRole(Jwt jwt, String role) {
        var realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) return false;

        var roles = (java.util.List<?>) realmAccess.get("roles");
        return roles != null && roles.contains(role);
    }
}