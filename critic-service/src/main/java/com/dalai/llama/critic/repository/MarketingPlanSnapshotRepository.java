package com.dalai.llama.critic.repository;

import com.dalai.llama.critic.domain.PlanSnapshotKind;
import com.dalai.llama.critic.domain.entity.MarketingPlanSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MarketingPlanSnapshotRepository extends JpaRepository<MarketingPlanSnapshot, UUID> {

    Optional<MarketingPlanSnapshot> findBySessionIdAndKind(UUID sessionId, PlanSnapshotKind kind);
}
