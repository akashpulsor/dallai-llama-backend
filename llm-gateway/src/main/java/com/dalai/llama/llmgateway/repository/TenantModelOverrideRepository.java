package com.dalai.llama.llmgateway.repository;

import com.dalai.llama.llmgateway.domain.entity.TenantModelOverride;
import com.dalai.llama.llmgateway.domain.entity.TenantModelOverrideId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantModelOverrideRepository extends JpaRepository<TenantModelOverride, TenantModelOverrideId> {

    Optional<TenantModelOverride> findByIdTenantIdAndIdModelId(String tenantId, String modelId);

    List<TenantModelOverride> findByIdTenantId(String tenantId);
}
