package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.CreativeDirectionGeneration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreativeDirectionGenerationRepository extends JpaRepository<CreativeDirectionGeneration, UUID> {

    Optional<CreativeDirectionGeneration> findTopByProjectIdOrderByRoundDesc(UUID projectId);

    List<CreativeDirectionGeneration> findByIdIn(List<UUID> ids);
}
