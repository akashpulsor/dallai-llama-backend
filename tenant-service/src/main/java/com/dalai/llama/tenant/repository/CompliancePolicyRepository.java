package com.dalai.llama.tenant.repository;

import com.dalai.llama.tenant.domain.entity.CompliancePolicy;
import com.dalai.llama.tenant.domain.entity.ProvisioningTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CompliancePolicyRepository extends JpaRepository<CompliancePolicy, UUID> {

    Optional<CompliancePolicy> findByTenantId(UUID tenantId);
}
