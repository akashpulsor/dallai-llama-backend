package com.dalai.llama.tenant.dto.response;

import com.dalai.llama.tenant.domain.entity.enums.AccountType;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Builder
public record MeResponse(
        UUID tenantId,
        String tenantSlug,
        String slug,
        boolean hasTenant,
        boolean needsOnboarding,
        boolean isActive,
        String name,
        String dashboardUrl,
        TenantStatus status,
        String statusMessage,
        String keycloakRealmName,
        UUID tenantUserId,
        String primaryRole,
        List<String> panels,
        String firstName,
        String lastName,
        String email,
        /** The creator's own markup (0-100) on the platform's standard rate for client-facing
         * pricing -- fetched here (rather than a separate call) since GET /tenants/me already
         * loads on every page. */
        BigDecimal marginPercent,
        /** Account-level authorization role -- gates creator-only UI (e.g. the project pricing
         * panel). Null only when {@code hasTenant} is false (no tenant resolved yet). */
        AccountType accountType
) {}
