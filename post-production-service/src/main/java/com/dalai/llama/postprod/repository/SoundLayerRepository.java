package com.dalai.llama.postprod.repository;

import com.dalai.llama.postprod.domain.entity.SoundLayer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SoundLayerRepository extends JpaRepository<SoundLayer, UUID> {

    List<SoundLayer> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    List<SoundLayer> findByProjectIdAndIncludedTrue(UUID projectId);

    Optional<SoundLayer> findByLayerIdAndTenantIdAndProjectId(UUID layerId, UUID tenantId, UUID projectId);
}
