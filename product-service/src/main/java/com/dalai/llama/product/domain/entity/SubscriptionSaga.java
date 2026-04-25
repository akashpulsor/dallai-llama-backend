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

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", nullable = false)
    private SagaStep currentStep;

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



    public void advanceTo(SagaStep step) {
        this.lastCompletedStep = this.currentStep;
        this.currentStep = step;
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

    public boolean hasCompletedStep(SagaStep step) {
        if (lastCompletedStep == null) return false;
        return lastCompletedStep.ordinal() >= step.ordinal();
    }
}