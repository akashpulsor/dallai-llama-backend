package com.dalai.llama.tenant.dto.response;


import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantResponse(

        UUID id,
        String name,
        String slug,
        TenantStatus status,
        String statusMessage,
        OffsetDateTime createdAt,
        OffsetDateTime activatedAt

) {}
