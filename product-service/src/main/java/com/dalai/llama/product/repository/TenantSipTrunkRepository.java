package com.dalai.llama.product.repository;

import com.dalai.llama.product.domain.entity.TenantSipTrunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantSipTrunkRepository extends JpaRepository<TenantSipTrunk, UUID> {

    Optional<TenantSipTrunk> findByTenantId(UUID tenantId);

    Optional<TenantSipTrunk> findByUsername(String username);

    Optional<TenantSipTrunk> findBySubscriptionId(UUID subscriptionId);
}