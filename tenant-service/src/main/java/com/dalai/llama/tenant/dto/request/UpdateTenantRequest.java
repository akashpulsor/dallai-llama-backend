package com.dalai.llama.tenant.dto.request;


import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateTenantRequest(

        @Size(max = 100)
        String name,

        @Size(max = 200)
        String companyName,

        @Email
        String primaryContactEmail,

        String primaryContactPhone,
        String timezone,

        /** The creator's own markup (0-100) on the platform's standard rate for client-facing
         * pricing. Null leaves it unchanged, same partial-update convention every other field
         * here already follows. */
        @DecimalMin(value = "0", inclusive = true)
        @DecimalMax(value = "100", inclusive = true)
        BigDecimal marginPercent
) {}
