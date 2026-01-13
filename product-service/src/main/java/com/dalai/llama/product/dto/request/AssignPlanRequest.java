package com.dalai.llama.product.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignPlanRequest {

    @NotNull
    private UUID planId;

    /**
     * Optional. Defaults to now if not provided.
     */
    private Instant effectiveFrom;
}
