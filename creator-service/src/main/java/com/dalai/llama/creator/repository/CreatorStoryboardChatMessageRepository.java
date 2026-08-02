package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorStoryboardChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CreatorStoryboardChatMessageRepository extends JpaRepository<CreatorStoryboardChatMessage, UUID> {

    List<CreatorStoryboardChatMessage> findByWorkspaceIdOrderByCreatedAtAsc(UUID workspaceId);

    List<CreatorStoryboardChatMessage> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId, Pageable pageable);

    long countByWorkspaceId(UUID workspaceId);
}
