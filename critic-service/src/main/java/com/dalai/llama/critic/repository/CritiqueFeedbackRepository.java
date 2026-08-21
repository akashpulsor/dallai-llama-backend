package com.dalai.llama.critic.repository;

import com.dalai.llama.critic.domain.entity.CritiqueFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CritiqueFeedbackRepository extends JpaRepository<CritiqueFeedback, UUID> {

    List<CritiqueFeedback> findBySessionId(UUID sessionId);

    List<CritiqueFeedback> findByTenantIdAndEmbeddingIsNotNull(UUID tenantId);
}
