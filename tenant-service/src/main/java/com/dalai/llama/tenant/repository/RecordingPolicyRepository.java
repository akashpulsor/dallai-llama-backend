package com.dalai.llama.tenant.repository;

import com.dalai.llama.tenant.domain.entity.CompliancePolicy;
import com.dalai.llama.tenant.domain.entity.RecordingPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RecordingPolicyRepository extends JpaRepository<RecordingPolicy, UUID> {

    Optional<RecordingPolicy> findByTenantId(UUID tenantId);
}
