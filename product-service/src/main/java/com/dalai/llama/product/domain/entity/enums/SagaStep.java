package com.dalai.llama.product.domain.entity.enums;

public enum SagaStep {
    PENDING,
    DID_PROVISIONED,
    SIP_ENDPOINT_CREATED,
    CHANNELS_ALLOCATED,
    TENANT_TRUNK_CREATED,
    PLAN_ASSIGNED,
    ACTIVATED,
    RECURRING_CHARGES_CREATED,
    COMPLETED,
    FAILED
}