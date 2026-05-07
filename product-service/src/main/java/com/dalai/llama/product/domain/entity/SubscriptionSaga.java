package com.dalai.llama.product.domain.entity;

import com.dalai.llama.product.domain.entity.enums.SagaStep;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subscription_saga")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionSaga {

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false, unique = true)
    private UUID subscriptionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "triggered_by_event_id")
    private UUID triggeredByEventId;

    /**
     * Current step. May be either an in-progress marker (e.g. PROVISIONING_DID)
     * set BEFORE attempting a step, or a completion marker (e.g. DID_PROVISIONED)
     * set AFTER the step succeeds. The failure log reads this to identify which
     * step was being attempted when an exception occurred.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", nullable = false)
    private SagaStep currentStep;

    /**
     * Last successfully completed checkpoint. Always a completion marker,
     * never an in-progress marker. Retry uses this to resume from the
     * step after the last successful checkpoint.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "last_completed_step")
    private SagaStep lastCompletedStep;

    @Enumerated(EnumType.STRING)
    @Column(name = "failed_step")
    private SagaStep failedStep;

    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    // Resource references (for compensation)
    @Column(name = "did_id")
    private UUID didId;

    @Column(name = "sip_endpoint_id")
    private UUID sipEndpointId;

    @Column(name = "channel_bundle_id")
    private UUID channelBundleId;

    @Column(name = "tenant_sip_trunk_id")
    private UUID tenantSipTrunkId;

    @Column(name = "plan_assignment_id")
    private UUID planAssignmentId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;


    /**
     * Mark a completion step as done. Both currentStep and lastCompletedStep
     * are set to the completion marker.
     *
     * Always pass a completion-marker step (e.g. DID_PROVISIONED), never an
     * in-progress marker (e.g. PROVISIONING_DID). In-progress markers are
     * set directly via setCurrentStep() before attempting a step body.
     */
    public void advanceTo(SagaStep completionStep) {
        this.lastCompletedStep = completionStep;
        this.currentStep = completionStep;
        this.lastUpdatedAt = Instant.now();
    }

    public void markFailed(SagaStep failedAt, String reason) {
        this.failedStep = failedAt;
        this.currentStep = SagaStep.FAILED;
        this.failureReason = reason != null && reason.length() > 2000
                ? reason.substring(0, 2000) : reason;
        this.failedAt = Instant.now();
        this.lastUpdatedAt = Instant.now();
    }

    public void markCompleted() {
        this.currentStep = SagaStep.COMPLETED;
        this.completedAt = Instant.now();
        this.lastUpdatedAt = Instant.now();
    }
}