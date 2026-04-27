package com.dalai.llama.tenant.domain.event;

import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProvisioningCompletedEvent {
    private UUID tenantId;
    private UUID tenantAppId;
    private UUID subscriptionId;
    private String productCode;
    private ProvisioningTaskStatus status;          // COMPLETED or FAILED
    private String failureReason;   // null if COMPLETED
    private Instant completedAt;
}