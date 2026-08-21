package com.dalai.llama.critic.repository;

import com.dalai.llama.critic.domain.entity.MarketingPlanCritiqueFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MarketingPlanCritiqueFindingRepository extends JpaRepository<MarketingPlanCritiqueFinding, UUID> {
}
