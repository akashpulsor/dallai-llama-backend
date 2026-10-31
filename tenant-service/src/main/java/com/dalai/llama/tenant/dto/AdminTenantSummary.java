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
        OffsetDateTime expiresAt,
        /* The creator's public profile page; null until they have a profile. */
        String publicProfileUrl,
        /* Videos showing on that profile; null with no profile. */
        Integer liveVideos
) {
    public static AdminTenantSummary from(Tenant t) {
        return from(t, null);
    }

    public static AdminTenantSummary from(Tenant t, com.dalai.llama.tenant.showcase.service.CreatorProfileLinks.Link profile) {
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
                t.getExpiresAt(),
                profile == null ? null : profile.url(),
                profile == null ? null : profile.liveVideos()
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
