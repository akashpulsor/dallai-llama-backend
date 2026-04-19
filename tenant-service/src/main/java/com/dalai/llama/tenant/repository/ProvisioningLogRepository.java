package com.dalai.llama.tenant.repository;

import com.dalai.llama.tenant.domain.entity.ProvisioningLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProvisioningLogRepository extends JpaRepository<ProvisioningLog, UUID> {

    List<ProvisioningLog> findByTenantAppIdOrderByStartedAtAsc(UUID tenantAppId);

    List<ProvisioningLog> findByTaskIdOrderByStartedAtAsc(UUID taskId);

    List<ProvisioningLog> findByTenantIdOrderByStartedAtDesc(UUID tenantId);
}