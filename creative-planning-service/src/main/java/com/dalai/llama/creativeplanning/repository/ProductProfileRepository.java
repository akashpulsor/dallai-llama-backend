package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.ProductProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductProfileRepository extends JpaRepository<ProductProfile, UUID> {

    List<ProductProfile> findByBrandContextId(UUID brandContextId);

    Optional<ProductProfile> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<ProductProfile> findByProjectRequirementId(UUID projectRequirementId);
}
