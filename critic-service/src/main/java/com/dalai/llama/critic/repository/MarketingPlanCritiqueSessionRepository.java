package com.dalai.llama.critic.repository;

import com.dalai.llama.critic.domain.entity.MarketingPlanCritiqueSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MarketingPlanCritiqueSessionRepository extends JpaRepository<MarketingPlanCritiqueSession, UUID> {
}
