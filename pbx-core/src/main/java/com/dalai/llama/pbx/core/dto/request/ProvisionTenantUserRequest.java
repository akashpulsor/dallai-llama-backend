package com.dalai.llama.pbx.core.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.Set;
import java.util.UUID;

@Builder
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ProvisionTenantUserRequest(
        UUID tenantId,
        String primaryRole,
        String firstName,
        String lastName,
        String email,
        String username,
        Set<String> additionalRoles,
        String createdBy
) {}
