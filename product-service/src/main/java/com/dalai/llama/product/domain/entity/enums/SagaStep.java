package com.dalai.llama.product.domain.entity.enums;

/**
 * Saga lifecycle for subscription provisioning.
 *
 * Steps come in pairs: an in-progress marker followed by a completion marker.
 * The in-progress marker is set BEFORE attempting the step body, so if the step
 * throws, the failure log names the actual step that was being attempted.
 *
 * The completion marker is set AFTER the step body succeeds, and is what
 * `lastCompletedStep` tracks for resume-from-failure retry logic.
 */
public enum SagaStep {
    PENDING,

    PROVISIONING_DID,
    DID_PROVISIONED,

    CREATING_SIP_ENDPOINT,
    SIP_ENDPOINT_CREATED,

    ALLOCATING_CHANNELS,
    CHANNELS_ALLOCATED,

    CREATING_TENANT_TRUNK,
    TENANT_TRUNK_CREATED,

    ASSIGNING_PLAN,
    PLAN_ASSIGNED,

    ACTIVATING,
    ACTIVATED,

    CREATING_RECURRING_CHARGES,
    RECURRING_CHARGES_CREATED,

    COMPLETED,
    FAILED
}