package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ShotProductReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShotProductReferenceRepository extends JpaRepository<ShotProductReference, UUID> {

    Optional<ShotProductReference> findByShotId(UUID shotId);

    List<ShotProductReference> findByTenantIdAndShotIdIn(UUID tenantId, List<UUID> shotIds);
}
