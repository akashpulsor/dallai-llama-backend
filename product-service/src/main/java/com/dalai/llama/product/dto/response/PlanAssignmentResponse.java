package com.dalai.llama.product.dto.response;


import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanAssignmentResponse {

    private UUID tenantId;
    private UUID planId;
    private String planCode;
    private Instant effectiveFrom;
    private Instant effectiveTo;
    private boolean active;
}
