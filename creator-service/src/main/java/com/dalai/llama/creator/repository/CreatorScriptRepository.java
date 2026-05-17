package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorScript;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CreatorScriptRepository extends JpaRepository<CreatorScript, UUID> {
    Optional<CreatorScript> findByIdAndTenantIdAndUserId(UUID id, String tenantId, String userId);
}
