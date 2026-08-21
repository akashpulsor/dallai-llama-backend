package com.dalai.llama.critic.repository;

import com.dalai.llama.critic.domain.entity.CritiquePlanContinuityAnchor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CritiquePlanContinuityAnchorRepository extends JpaRepository<CritiquePlanContinuityAnchor, UUID> {

    List<CritiquePlanContinuityAnchor> findBySnapshotId(UUID snapshotId);
}
