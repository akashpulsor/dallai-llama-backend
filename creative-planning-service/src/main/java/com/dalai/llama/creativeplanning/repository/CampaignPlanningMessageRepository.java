package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.CampaignPlanningMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CampaignPlanningMessageRepository extends JpaRepository<CampaignPlanningMessage, UUID> {

    List<CampaignPlanningMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    long countBySessionId(UUID sessionId);
}
