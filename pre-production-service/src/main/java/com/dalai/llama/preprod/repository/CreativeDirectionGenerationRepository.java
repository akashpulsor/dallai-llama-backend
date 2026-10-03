package com.dalai.llama.preprod.repository;

import com.dalai.llama.joblifecycle.JobLifecycleStatus;
import com.dalai.llama.preprod.domain.entity.CreativeDirectionGeneration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreativeDirectionGenerationRepository extends JpaRepository<CreativeDirectionGeneration, UUID> {

    Optional<CreativeDirectionGeneration> findTopByProjectIdOrderByRoundDesc(UUID projectId);

    Optional<CreativeDirectionGeneration> findTopByProjectIdAndStatusOrderByRoundDesc(UUID projectId, JobLifecycleStatus status);

    Optional<CreativeDirectionGeneration> findByLlmIdempotencyKey(String llmIdempotencyKey);

    List<CreativeDirectionGeneration> findByIdIn(List<UUID> ids);
}
