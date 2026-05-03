package com.dalai.llama.tenant.dto.response;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import lombok.Builder;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Lightweight summary of a TenantApp for the "my apps" sidebar.
 * Includes deployment status so the UI can show active/failed/pending states
 * and offer a retry action.
 */
@Builder
public record TenantAppSummary(
        UUID id,
        UUID tenantId,
        String appType,
        String displayName,
        String subdomain,
        String productCode,
        String planCode,
        String planTier,
        String didNumber,
        ProvisioningTaskStatus deploymentStatus,
        UUID subscriptionId,
        OffsetDateTime createdAt,
        Instant deployedAt
) {

    public static TenantAppSummary from(TenantApp app) {
        return TenantAppSummary.builder()
                .id(app.getId())
                .tenantId(app.getTenant() != null ? app.getTenant().getId() : null)
                .appType(app.getAppType() != null ? app.getAppType().name() : null)
                .displayName(app.getDisplayName())
                .subdomain(app.getSubdomain())
                .productCode(app.getProductCode())
                .planCode(app.getPlanCode())
                .planTier(app.getPlanTier())
                .didNumber(app.getDidNumber())
                .deploymentStatus(app.getDeploymentStatus())
                .subscriptionId(app.getSubscriptionId())
                .createdAt(app.getCreatedAt())
                .deployedAt(app.getDeployedAt())
                .build();
    }
}
