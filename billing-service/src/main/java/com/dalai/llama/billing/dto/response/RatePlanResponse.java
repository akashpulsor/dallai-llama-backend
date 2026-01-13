package com.dalai.llama.billing.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public class RatePlanResponse {
    private UUID id;
    private String code;
    private String name;
    private String description;
    private boolean isDefault;
    private boolean active;
}
