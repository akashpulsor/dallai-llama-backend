package com.dalai.llama.tenant.dto.response;

import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import lombok.Builder;

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
        String email
) {}
