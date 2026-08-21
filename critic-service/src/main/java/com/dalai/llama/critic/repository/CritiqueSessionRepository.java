package com.dalai.llama.critic.repository;

import com.dalai.llama.critic.domain.entity.CritiqueSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CritiqueSessionRepository extends JpaRepository<CritiqueSession, UUID> {

    Optional<CritiqueSession> findByIdAndTenantId(UUID id, UUID tenantId);

    List<CritiqueSession> findByShotIdOrderByCreatedAtDesc(UUID shotId);
}
