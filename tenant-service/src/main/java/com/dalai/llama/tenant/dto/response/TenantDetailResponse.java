package com.dalai.llama.tenant.dto.response;

import com.dalai.llama.tenant.domain.entity.enums.DeploymentModel;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantDetailResponse(

        UUID id,
        String name,
        String slug,
        String companyName,

        TenantStatus status,
        String substatus,
        String statusMessage,

        DeploymentModel deploymentModel,
        String namespace,

        UUID planId,
        String planCode,

        UUID walletId,
        String billingState,

        String sipExternalIp,
        String dashboardUrl,

        OffsetDateTime createdAt,
        OffsetDateTime activatedAt,
        OffsetDateTime suspendedAt,
        OffsetDateTime deletedAt
) {}
