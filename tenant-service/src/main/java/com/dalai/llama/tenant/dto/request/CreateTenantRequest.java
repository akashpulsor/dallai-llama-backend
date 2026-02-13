package com.dalai.llama.tenant.dto.request;


import com.dalai.llama.tenant.domain.entity.enums.DeploymentModel;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTenantRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        @NotBlank
        @Size(max = 200)
        String companyName,

        @NotBlank
        String primaryContactName,

        @NotBlank
        @Email
        String primaryContactEmail,

        String primaryContactPhone,

        @Size(max = 2)
        String country,
        String timezone,


        String productCode,

        DeploymentModel deploymentModel

) {}
