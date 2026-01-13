package com.dalai.llama.product.service.impl;

import com.dalai.llama.product.domain.entity.Plan;
import com.dalai.llama.product.domain.entity.PlanAssignment;
import com.dalai.llama.product.domain.event.PlanAssignedEvent;
import com.dalai.llama.product.domain.event.PlanChangedEvent;
import com.dalai.llama.product.domain.exception.PlanNotFoundException;
import com.dalai.llama.product.kafka.producer.ProductEventProducer;
import com.dalai.llama.product.repository.PlanAssignmentRepository;
import com.dalai.llama.product.repository.PlanRepository;
import com.dalai.llama.product.service.EntitlementService;
import com.dalai.llama.product.service.PlanAssignmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PlanAssignmentServiceImpl implements PlanAssignmentService {

    private final PlanAssignmentRepository assignmentRepository;
    private final PlanRepository planRepository;
    private final ProductEventProducer eventProducer;
    private final EntitlementService entitlementService;

    @Override
    public PlanAssignment assignPlan(UUID tenantId, UUID planId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new PlanNotFoundException(planId.toString()));

        // Check for existing active assignment
        var existingOpt = assignmentRepository.findActiveByTenantId(tenantId);

        if (existingOpt.isPresent()) {
            PlanAssignment existing = existingOpt.get();

            // Deactivate old plan
            existing.setActive(false);
            existing.setEffectiveTo(Instant.now());
            existing.setUpdatedAt(Instant.now());
            assignmentRepository.save(existing);

            // Create new assignment
            PlanAssignment newAssignment = createAssignment(tenantId, plan);

            // Publish plan changed event
            eventProducer.publishPlanChanged(PlanChangedEvent.builder()
                    .tenantId(tenantId)
                    .oldPlanId(existing.getPlan().getId())
                    .oldPlanCode(existing.getPlan().getCode())
                    .newPlanId(plan.getId())
                    .newPlanCode(plan.getCode())
                    .effectiveFrom(newAssignment.getEffectiveFrom())
                    .occurredAt(Instant.now())
                    .build());

            // Invalidate cache
            entitlementService.invalidateCache(tenantId);

            return newAssignment;
        }

        // First time assignment
        PlanAssignment assignment = createAssignment(tenantId, plan);

        eventProducer.publishPlanAssigned(PlanAssignedEvent.builder()
                .tenantId(tenantId)
                .planId(plan.getId())
                .planCode(plan.getCode())
                .productCode(plan.getProduct().getCode())
                .effectiveFrom(assignment.getEffectiveFrom())
                .occurredAt(Instant.now())
                .build());

        return assignment;
    }

    @Override
    @Transactional(readOnly = true)
    public PlanAssignment getActivePlan(UUID tenantId) {
        return assignmentRepository.findActiveByTenantId(tenantId)
                .orElseThrow(() -> new PlanNotFoundException("No active plan for tenant: " + tenantId));
    }

    @Override
    public void removePlan(UUID tenantId) {
        assignmentRepository.findActiveByTenantId(tenantId).ifPresent(assignment -> {
            assignment.setActive(false);
            assignment.setEffectiveTo(Instant.now());
            assignment.setUpdatedAt(Instant.now());
            assignmentRepository.save(assignment);
            entitlementService.invalidateCache(tenantId);
            log.info("Removed plan assignment for tenant: {}", tenantId);
        });
    }

    private PlanAssignment createAssignment(UUID tenantId, Plan plan) {
        PlanAssignment assignment = PlanAssignment.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .plan(plan)
                .effectiveFrom(Instant.now())
                .active(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return assignmentRepository.save(assignment);
    }
}