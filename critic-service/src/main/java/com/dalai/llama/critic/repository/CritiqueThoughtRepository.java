package com.dalai.llama.critic.repository;

import com.dalai.llama.critic.domain.entity.CritiqueThought;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CritiqueThoughtRepository extends JpaRepository<CritiqueThought, UUID> {

    List<CritiqueThought> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
