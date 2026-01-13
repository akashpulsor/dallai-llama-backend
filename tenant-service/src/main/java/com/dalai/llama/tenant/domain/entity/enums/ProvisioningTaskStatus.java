package com.dalai.llama.tenant.domain.entity.enums;


public enum ProvisioningTaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    COMPENSATING,
    COMPENSATED
}
