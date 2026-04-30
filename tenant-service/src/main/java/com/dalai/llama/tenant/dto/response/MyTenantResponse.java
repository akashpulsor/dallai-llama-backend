package com.dalai.llama.tenant.dto.response;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import lombok.Builder;

import java.util.UUID;

@Builder
public record MyTenantResponse(
        boolean hasTenant,
        boolean needsOnboarding,
        boolean isActive,
        UUID tenantId,
        String slug,
        String name,
        String dashboardUrl,
        TenantStatus status,
        String statusMessage,
        String keycloakRealmName
) {
    public static MyTenantResponse empty() {
        return MyTenantResponse.builder()
                .hasTenant(false)
                .needsOnboarding(true)
                .isActive(false)
                .build();
    }
    public static MyTenantResponse fromTenant(Tenant t) {
        return MyTenantResponse.builder()
                .hasTenant(true)
                .needsOnboarding(false)
                .isActive(t.getStatus() == TenantStatus.ACTIVE)
                .tenantId(t.getId())
                .slug(t.getSlug())
                .name(t.getName())
                .status(t.getStatus())
                .statusMessage(t.getStatusMessage())
                .keycloakRealmName(t.getKeycloakRealmName())
                .dashboardUrl("https://admin-" + t.getSlug() + ".dalaillama.in")
                .build();
    }
}