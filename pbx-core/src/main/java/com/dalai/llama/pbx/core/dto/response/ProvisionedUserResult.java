package com.dalai.llama.pbx.core.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ProvisionedUserResult(
        UUID tenantUserId,
        String keycloakUserId,
        UUID credentialDeliveryId,
        String loginUrl
) {}
