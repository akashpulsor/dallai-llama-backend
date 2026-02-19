package com.dalai.llama.tenant.dto.request;

import java.util.UUID;

public record TenantInfo(
        UUID id,
        String name,
        String slug,
        String status,
        UUID adminUserId,
        String adminUserEmail
) {}