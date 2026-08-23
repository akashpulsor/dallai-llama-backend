package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.IdeaOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdeaOptionRepository extends JpaRepository<IdeaOption, UUID> {

    List<IdeaOption> findByProjectRequirementIdOrderByCreatedAtDesc(UUID projectRequirementId);

    Optional<IdeaOption> findByIdAndTenantId(UUID id, UUID tenantId);
}
