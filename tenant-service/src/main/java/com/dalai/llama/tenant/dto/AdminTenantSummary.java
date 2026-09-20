package com.dalai.llama.tenant.dto;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Admin-facing summary of a {@link Tenant} for the ops dashboard's Tenants tab. Narrower than
 * the full entity: skips the Kafka topic / GSTIN / recording detail fields that are irrelevant
 * to a "who is on the platform, what state are they in" scan; keeps the fields an operator needs
 * to pick a tenant and act on it (name / email / status / trial expiry / keycloak_configured).
 */
public record AdminTenantSummary(
        UUID id,
        String name,
        String slug,
        String primaryContactEmail,
        String primaryContactName,
        UUID adminUserId,
        TenantStatus status,
        String substatus,
        String statusMessage,
        Boolean keycloakConfigured,
        String accountType,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt
) {
    public static AdminTenantSummary from(Tenant t) {
        return new AdminTenantSummary(
                t.getId(),
                t.getName(),
                t.getSlug(),
                t.getPrimaryContactEmail(),
                t.getPrimaryContactName(),
                t.getAdminUserId() == null ? null : safeUuid(t.getAdminUserId()),
                t.getStatus(),
                t.getSubstatus(),
                t.getStatusMessage(),
                // Entity does not map keycloak_configured directly; derive from realm-name
                // presence (both are set atomically by the bootstrap job, so this is equivalent
                // to the DB column). Cheaper than adding a field mapping for one endpoint.
                t.getKeycloakRealmName() != null && !t.getKeycloakRealmName().isBlank(),
                t.getAccountType() == null ? null : t.getAccountType().name(),
                t.getCreatedAt(),
                t.getExpiresAt()
        );
    }

    private static UUID safeUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
