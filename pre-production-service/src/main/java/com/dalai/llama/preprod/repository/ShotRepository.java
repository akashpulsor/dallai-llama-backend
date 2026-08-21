package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.Shot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShotRepository extends JpaRepository<Shot, UUID> {

    List<Shot> findByProjectIdOrderByShotNumberAsc(UUID projectId);

    List<Shot> findByScreenplaySceneIdOrderByShotNumberAsc(UUID screenplaySceneId);

    Optional<Shot> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Shot> findByProjectIdAndShotRef(UUID projectId, String shotRef);
}
