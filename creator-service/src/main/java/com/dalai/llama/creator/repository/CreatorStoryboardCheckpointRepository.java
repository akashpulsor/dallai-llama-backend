package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorStoryboardCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CreatorStoryboardCheckpointRepository extends JpaRepository<CreatorStoryboardCheckpoint, UUID> {

    List<CreatorStoryboardCheckpoint> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);
}
