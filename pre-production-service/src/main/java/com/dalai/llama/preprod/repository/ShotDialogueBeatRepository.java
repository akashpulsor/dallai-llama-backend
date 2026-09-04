package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ShotDialogueBeat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShotDialogueBeatRepository extends JpaRepository<ShotDialogueBeat, UUID> {

    List<ShotDialogueBeat> findByShotIdOrderByOrderIndexAsc(UUID shotId);

    Optional<ShotDialogueBeat> findByIdAndTenantId(UUID id, UUID tenantId);

    void deleteByIdAndTenantId(UUID id, UUID tenantId);
}
