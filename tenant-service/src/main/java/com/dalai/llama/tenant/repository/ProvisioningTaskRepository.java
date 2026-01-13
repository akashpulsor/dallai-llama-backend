package com.dalai.llama.tenant.repository;


import com.dalai.llama.tenant.domain.entity.ProvisioningTask;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProvisioningTaskRepository extends JpaRepository<ProvisioningTask, UUID> {

    Optional<ProvisioningTask> findFirstByTenantIdAndStatusIn(
            UUID tenantId,
            Iterable<ProvisioningTaskStatus> statuses
    );

    boolean existsByTenantIdAndStatus(UUID tenantId, ProvisioningTaskStatus status);
}
