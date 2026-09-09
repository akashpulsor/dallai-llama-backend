package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.CameraPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CameraPlanRepository extends JpaRepository<CameraPlan, UUID> {

    Optional<CameraPlan> findByShotId(UUID shotId);

    List<CameraPlan> findByTenantIdAndShotIdIn(UUID tenantId, Collection<UUID> shotIds);
}
