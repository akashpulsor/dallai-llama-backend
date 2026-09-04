package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.ClientReviewSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientReviewSessionRepository extends JpaRepository<ClientReviewSession, UUID> {

    /** The single OPEN review for a project, if the client is mid-review. */
    Optional<ClientReviewSession> findFirstByProjectIdAndStatus(UUID projectId, String status);

    /** Every review ever started for a project -- its size is the reviews-used count. */
    List<ClientReviewSession> findByProjectIdOrderByStartedAtAsc(UUID projectId);

    long countByProjectId(UUID projectId);
}
