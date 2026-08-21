package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.LockedIdea;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LockedIdeaRepository extends JpaRepository<LockedIdea, UUID> {

    Optional<LockedIdea> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<LockedIdea> findBySessionId(UUID sessionId);
}
