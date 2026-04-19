package com.dalai.llama.tenant.domain.entity;

import com.dalai.llama.tenant.domain.entity.enums.ProvisioningStep;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable log of every provisioning step attempt.
 * One row per attempt — never updated, only inserted.
 * Exposed via API for troubleshooting.
 */
@Entity
@Table(name = "provisioning_logs", indexes = {
        @Index(name = "idx_prov_log_tenant", columnList = "tenant_app_id"),
        @Index(name = "idx_prov_log_task", columnList = "task_id")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ProvisioningLog {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "tenant_app_id", nullable = false)
    private UUID tenantAppId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ProvisioningStep step;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProvisioningTaskStatus status;

    private int attemptNumber;

    @Column(length = 2000)
    private String message;

    @Column(length = 2000)
    private String errorDetail;

    private long durationMs;

    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;

    @PrePersist
    void onCreate() {
        if (startedAt == null) startedAt = OffsetDateTime.now();
    }
}