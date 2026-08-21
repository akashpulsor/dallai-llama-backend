package com.dalai.llama.critic.repository;

import com.dalai.llama.critic.domain.PlanSnapshotKind;
import com.dalai.llama.critic.domain.entity.CritiquePlanSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CritiquePlanSnapshotRepository extends JpaRepository<CritiquePlanSnapshot, UUID> {

    Optional<CritiquePlanSnapshot> findBySessionIdAndKind(UUID sessionId, PlanSnapshotKind kind);
}
