package com.dalai.llama.product.dto.request;

import com.dalai.llama.product.domain.entity.enums.RoutingTargetType;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateDidRoutingRequest {

    private RoutingTargetType routingTargetType;
    private String routingTargetId;

    private RoutingTargetType fallbackTargetType;
    private String fallbackTargetId;

    private boolean recordingEnabled;
    private boolean transcriptionEnabled;
    private boolean sentimentEnabled;

    private boolean businessHoursOnly;

    /**
     * Format: HH:mm
     */
    private String businessHoursStart;

    /**
     * Format: HH:mm
     */
    private String businessHoursEnd;
}
