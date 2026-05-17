package com.dalai.llama.tenant.dto.request;

import com.dalai.llama.tenant.domain.entity.enums.UserRole;
import lombok.Builder;

import java.util.Set;
import java.util.UUID;

@Builder
public record ProvisionTenantUserRequest(
        UUID tenantId,
        UserRole primaryRole,
        String firstName,
        String lastName,
        String email,
        String username,
        Set<String> additionalRoles,
        String createdBy
) {}
