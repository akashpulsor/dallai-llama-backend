package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorStoryboardWorkspaceVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorStoryboardWorkspaceVersionRepository
        extends JpaRepository<CreatorStoryboardWorkspaceVersion, UUID> {

    Optional<CreatorStoryboardWorkspaceVersion> findByWorkspaceIdAndVersion(UUID workspaceId, Integer version);

    List<CreatorStoryboardWorkspaceVersion> findByWorkspaceIdOrderByVersionDesc(UUID workspaceId);
}
