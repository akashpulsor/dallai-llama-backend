package com.dalai.llama.tenant.domain.event;

import com.dalai.llama.tenant.domain.entity.enums.ProvisioningStep;

import java.util.UUID;

public record ProvisioningStepCompletedEvent (UUID tenantId, ProvisioningStep step) {}