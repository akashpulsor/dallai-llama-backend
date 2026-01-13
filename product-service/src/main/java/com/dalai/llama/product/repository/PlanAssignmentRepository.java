package com.dalai.llama.product.repository;

import com.dalai.llama.product.domain.entity.PlanAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanAssignmentRepository extends JpaRepository<PlanAssignment, UUID> {

    @Query("""
        SELECT pa FROM PlanAssignment pa
        WHERE pa.tenantId = :tenantId
          AND pa.active = true
          AND (pa.effectiveTo IS NULL OR pa.effectiveTo > CURRENT_TIMESTAMP)
        """)
    Optional<PlanAssignment> findActiveByTenantId(UUID tenantId);

    List<PlanAssignment> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
