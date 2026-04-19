package com.dalai.llama.tenant.repository;

import com.dalai.llama.tenant.domain.entity.ProvisioningTask;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProvisioningTaskRepository extends JpaRepository<ProvisioningTask, UUID> {

    Optional<ProvisioningTask> findFirstByTenantIdAndStatusIn(
            UUID tenantId, Iterable<ProvisioningTaskStatus> statuses);

    Optional<ProvisioningTask> findFirstByTenantAppIdAndStatusIn(
            UUID tenantAppId, Iterable<ProvisioningTaskStatus> statuses);

    Optional<ProvisioningTask> findFirstByTenantAppIdOrderByStartedAtDesc(UUID tenantAppId);

    boolean existsByTenantIdAndStatus(UUID tenantId, ProvisioningTaskStatus status);

    List<ProvisioningTask> findByStatusAndStartedAtBefore(ProvisioningTaskStatus status, OffsetDateTime cutoff);

    List<ProvisioningTask> findByStatusAndLastErrorAtBefore(ProvisioningTaskStatus status, OffsetDateTime cutoff);
}