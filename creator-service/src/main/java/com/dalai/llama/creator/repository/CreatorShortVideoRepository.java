package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorShortVideo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorShortVideoRepository extends JpaRepository<CreatorShortVideo, UUID> {

    Optional<CreatorShortVideo> findByIdAndTenantIdAndUserId(UUID id, String tenantId, String userId);

    List<CreatorShortVideo> findTop20ByTenantIdAndUserIdOrderByCreatedAtDesc(String tenantId, String userId);

    List<CreatorShortVideo> findTop20BySourceAssetIdOrderByUpdatedAtDesc(UUID sourceAssetId);
}
