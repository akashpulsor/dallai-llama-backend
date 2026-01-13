package com.dalai.llama.product.service.impl;

import com.dalai.llama.product.domain.entity.PlanAssignment;
import com.dalai.llama.product.domain.entity.PlanEntitlement;
import com.dalai.llama.product.domain.entity.enums.DidStatus;
import com.dalai.llama.product.domain.exception.EntitlementExceededException;
import com.dalai.llama.product.domain.exception.PlanNotFoundException;
import com.dalai.llama.product.repository.DidRepository;
import com.dalai.llama.product.repository.PlanAssignmentRepository;
import com.dalai.llama.product.repository.PlanEntitlementRepository;
import com.dalai.llama.product.service.EntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EntitlementServiceImpl implements EntitlementService {

    private final PlanAssignmentRepository assignmentRepository;
    private final PlanEntitlementRepository entitlementRepository;
    private final DidRepository didRepository;

    private static final String CACHE_NAME = "entitlements";

    @Override
    @Cacheable(value = CACHE_NAME, key = "'tenant:' + #tenantId")
    public PlanEntitlement getEffectiveEntitlements(UUID tenantId) {
        log.debug("Fetching entitlements for tenant: {}", tenantId);

        PlanAssignment assignment = assignmentRepository.findActiveByTenantId(tenantId)
                .orElseThrow(() -> new PlanNotFoundException("No active plan for tenant: " + tenantId));

        return entitlementRepository.findByPlan_Id(assignment.getPlan().getId())
                .orElseThrow(() -> new PlanNotFoundException(
                        "No entitlements for plan: " + assignment.getPlan().getCode()));
    }

    @Override
    public void validateDidLimit(UUID tenantId) {
        PlanEntitlement entitlements = getEffectiveEntitlements(tenantId);

        long currentDids = didRepository.countByTenantIdAndStatusIn(
                tenantId,
                List.of(DidStatus.ACTIVE, DidStatus.PENDING, DidStatus.PROVISIONING)
        );

        if (currentDids >= entitlements.getMaxDids()) {
            throw new EntitlementExceededException(
                    "DID limit exceeded. Max: " + entitlements.getMaxDids() + ", Current: " + currentDids);
        }
    }

    @Override
    @CacheEvict(value = CACHE_NAME, key = "'tenant:' + #tenantId")
    public void invalidateCache(UUID tenantId) {
        log.info("Invalidated entitlement cache for tenant: {}", tenantId);
    }
}