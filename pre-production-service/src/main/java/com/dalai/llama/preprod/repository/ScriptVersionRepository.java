package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ScriptVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScriptVersionRepository extends JpaRepository<ScriptVersion, UUID> {

    Optional<ScriptVersion> findTopByProjectIdOrderByVersionDesc(UUID projectId);

    Optional<ScriptVersion> findByProjectIdAndVersion(UUID projectId, Integer version);

    List<ScriptVersion> findByProjectIdOrderByVersionAsc(UUID projectId);
}
