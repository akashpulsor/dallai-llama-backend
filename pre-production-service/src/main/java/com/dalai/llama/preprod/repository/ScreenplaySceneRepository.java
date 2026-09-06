package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ScreenplayScene;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScreenplaySceneRepository extends JpaRepository<ScreenplayScene, UUID> {

    List<ScreenplayScene> findByScreenplayIdOrderBySceneNumberAsc(UUID screenplayId);

    Optional<ScreenplayScene> findByIdAndTenantId(UUID id, UUID tenantId);
}
