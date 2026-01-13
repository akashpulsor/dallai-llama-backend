package com.dalai.llama.tenant.dto.request;

import jakarta.validation.constraints.NotBlank;

public record SuspendTenantRequest(

        @NotBlank
        String reason
) {}
