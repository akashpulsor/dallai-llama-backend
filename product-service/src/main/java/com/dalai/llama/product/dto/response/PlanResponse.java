package com.dalai.llama.product.dto.response;

import com.dalai.llama.product.domain.entity.enums.PlanTier;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanResponse {

    private UUID id;
    private String code;
    private String name;
    private String description;
    private PlanTier tier;
    private BigDecimal monthlyPrice;
    private String currency;
    private boolean isDefault;
}
