package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorShortCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorShortCandidateRepository extends JpaRepository<CreatorShortCandidate, UUID> {

    List<CreatorShortCandidate> findByVideoIdOrderByRankIndexAsc(UUID videoId);

    Optional<CreatorShortCandidate> findByIdAndVideoId(UUID id, UUID videoId);

    List<CreatorShortCandidate> findTop150ByTenantIdAndStatusInOrderByUpdatedAtDesc(String tenantId, Collection<String> statuses);

    List<CreatorShortCandidate> findTop150ByTenantIdAndReviewStatusInOrderByUpdatedAtDesc(String tenantId, Collection<String> reviewStatuses);
}