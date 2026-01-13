package com.dalai.llama.billing.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class CreateRatePlanRequest {

    @NotBlank
    private String code;

    @NotBlank
    private String name;

    private String description;
}
