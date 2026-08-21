package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BrandContextRepository extends JpaRepository<BrandContext, UUID> {

    Optional<BrandContext> findByTenantId(UUID tenantId);
}
