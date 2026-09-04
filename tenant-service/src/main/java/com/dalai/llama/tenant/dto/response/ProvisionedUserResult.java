package com.dalai.llama.tenant.dto.response;

import lombok.Builder;

import java.util.UUID;

@Builder
public record ProvisionedUserResult(
        UUID tenantUserId,
        String keycloakUserId,
        UUID credentialDeliveryId,
        String loginUrl
) {}
