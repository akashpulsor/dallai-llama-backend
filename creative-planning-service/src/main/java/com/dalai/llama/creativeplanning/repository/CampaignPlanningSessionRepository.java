package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.CampaignPlanningSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CampaignPlanningSessionRepository extends JpaRepository<CampaignPlanningSession, UUID> {

    Optional<CampaignPlanningSession> findByIdAndTenantId(UUID id, UUID tenantId);

    List<CampaignPlanningSession> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<CampaignPlanningSession> findByProductProfileIdOrderByCreatedAtDesc(UUID productProfileId);
}
