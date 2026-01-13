package com.dalai.llama.product.service;



import com.dalai.llama.product.domain.entity.PlanAssignment;

import java.util.UUID;

public interface PlanAssignmentService {

    PlanAssignment assignPlan(UUID tenantId, UUID planId);

    PlanAssignment getActivePlan(UUID tenantId);

    void removePlan(UUID tenantId);
}
