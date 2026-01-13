package com.dalai.llama.tenant.dto.request;


import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateTenantRequest(

        @Size(max = 100)
        String name,

        @Size(max = 200)
        String companyName,

        @Email
        String primaryContactEmail,

        String primaryContactPhone,
        String timezone
) {}
