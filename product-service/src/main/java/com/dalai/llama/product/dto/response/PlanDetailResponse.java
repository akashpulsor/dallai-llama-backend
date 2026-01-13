package com.dalai.llama.product.dto.response;


import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanDetailResponse {

    private PlanResponse plan;
    private EntitlementResponse entitlements;
}
