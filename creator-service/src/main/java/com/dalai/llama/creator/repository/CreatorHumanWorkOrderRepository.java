package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorHumanWorkOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorHumanWorkOrderRepository extends JpaRepository<CreatorHumanWorkOrder, UUID> {
    Optional<CreatorHumanWorkOrder> findByIdAndTenantIdAndUserId(UUID id, String tenantId, String userId);

    List<CreatorHumanWorkOrder> findByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId, Pageable pageable);

    List<CreatorHumanWorkOrder> findByScriptIdAndTenantIdAndUserIdOrderByUpdatedAtDesc(UUID scriptId, String tenantId, String userId);

    List<CreatorHumanWorkOrder> findByWorkTypeInAndStatusInOrderBySubmittedAtDesc(
            Collection<String> workTypes,
            Collection<String> statuses,
            Pageable pageable
    );

    List<CreatorHumanWorkOrder> findByWorkTypeInAndStatusInAndAssignedToOrderBySubmittedAtDesc(
            Collection<String> workTypes,
            Collection<String> statuses,
            String assignedTo,
            Pageable pageable
    );

    List<CreatorHumanWorkOrder> findByWorkTypeInAndStatusInAndAssignedToIsNullOrderBySubmittedAtDesc(
            Collection<String> workTypes,
            Collection<String> statuses,
            Pageable pageable
    );

    List<CreatorHumanWorkOrder> findByWorkTypeAndStatusAndAssignedToIsNullOrderBySubmittedAtAsc(
            String workType,
            String status,
            Pageable pageable
    );

    long countByAssignedToAndStatusIn(String assignedTo, Collection<String> statuses);
}
