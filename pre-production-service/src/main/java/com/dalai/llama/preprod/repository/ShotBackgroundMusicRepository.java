package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ShotBackgroundMusic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShotBackgroundMusicRepository extends JpaRepository<ShotBackgroundMusic, UUID> {

    Optional<ShotBackgroundMusic> findByShotIdAndTenantId(UUID shotId, UUID tenantId);
    List<ShotBackgroundMusic> findByTenantIdAndShotIdIn(UUID tenantId, List<UUID> shotIds);
}
