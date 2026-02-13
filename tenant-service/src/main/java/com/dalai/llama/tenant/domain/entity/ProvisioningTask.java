package com.dalai.llama.tenant.domain.entity;

import com.dalai.llama.tenant.domain.entity.enums.ProvisioningStep;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "provisioning_tasks")
@Getter
@Setter
public class ProvisioningTask {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    private ProvisioningTaskStatus status;

    @Enumerated(EnumType.STRING)
    private ProvisioningStep currentStep;

    private String currentStepStatus;
    private OffsetDateTime currentStepStartedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "completed_steps")
    private String completedSteps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "step_results")
    private String stepResults;

    private int retryCount;
    private int maxRetries;

    private String lastError;
    private OffsetDateTime lastErrorAt;

    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;

    @Version
    private long version;
}
