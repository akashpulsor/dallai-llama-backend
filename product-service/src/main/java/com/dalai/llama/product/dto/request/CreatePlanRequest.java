package com.dalai.llama.product.dto.request;

import com.dalai.llama.product.domain.entity.enums.PlanTier;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePlanRequest {

    @NotNull
    private UUID productId;

    @NotBlank
    private String code;

    @NotBlank
    private String name;

    private String description;

    @NotNull
    private PlanTier tier;

    @DecimalMin("0.0")
    private BigDecimal monthlyPrice;

    private boolean isDefault;
}
