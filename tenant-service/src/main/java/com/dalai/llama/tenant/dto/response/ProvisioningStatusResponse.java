package com.dalai.llama.tenant.dto.response;

import com.dalai.llama.tenant.domain.entity.enums.ProvisioningStep;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import java.time.OffsetDateTime;

public record ProvisioningStatusResponse(

        ProvisioningTaskStatus status,
        ProvisioningStep currentStep,
        String currentStepStatus,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        String lastError
) {}
