package com.dalai.llama.tenant.repository;

import com.dalai.llama.tenant.domain.entity.ProvisioningTask;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProvisioningTaskRepository extends JpaRepository<ProvisioningTask, UUID> {

    Optional<ProvisioningTask> findFirstByTenantIdAndStatusIn(
            UUID tenantId, Iterable<ProvisioningTaskStatus> statuses);

    Optional<ProvisioningTask> findFirstByTenantAppIdAndStatusInOrderByStartedAtDesc(
            UUID tenantAppId, Iterable<ProvisioningTaskStatus> statuses);

    Optional<ProvisioningTask> findFirstByTenantAppIdOrderByStartedAtDesc(UUID tenantAppId);

    @Modifying
    void deleteAllByTenantAppId(UUID tenantAppId);

    boolean existsByTenantIdAndStatus(UUID tenantId, ProvisioningTaskStatus status);

    List<ProvisioningTask> findByStatusAndStartedAtBefore(ProvisioningTaskStatus status, OffsetDateTime cutoff);

    List<ProvisioningTask> findByStatusAndLastErrorAtBefore(ProvisioningTaskStatus status, OffsetDateTime cutoff);

    @Modifying
    @Query("UPDATE ProvisioningTask t SET t.status = 'CANCELLED', " +
            "t.lastError = :reason, t.lastErrorAt = :now " +
            "WHERE t.tenant.id = :tenantId AND t.status IN :activeStatuses")
    int cancelAllForTenant(@Param("tenantId") UUID tenantId,
                           @Param("activeStatuses") List<ProvisioningTaskStatus> activeStatuses,
                           @Param("reason") String reason,
                           @Param("now") OffsetDateTime now);


}