package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorProfileRepository extends JpaRepository<CreatorProfile, UUID> {

    List<CreatorProfile> findByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId);

    Optional<CreatorProfile> findByIdAndTenantIdAndUserId(UUID id, String tenantId, String userId);
}
