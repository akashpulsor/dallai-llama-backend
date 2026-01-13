package com.dalai.llama.product.service.impl;

import com.dalai.llama.product.domain.entity.Plan;
import com.dalai.llama.product.domain.exception.PlanNotFoundException;
import com.dalai.llama.product.repository.PlanRepository;
import com.dalai.llama.product.service.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlanServiceImpl implements PlanService {

    private final PlanRepository planRepository;

    @Override
    public List<Plan> getAllPlans() {
        return planRepository.findAll();
    }

    @Override
    public List<Plan> getPlansByProductCode(String productCode) {
        return planRepository.findByProduct_CodeAndActiveTrue(productCode);
    }

    @Override
    public Plan getPlanByCode(String code) {
        return planRepository.findByCode(code)
                .orElseThrow(() -> new PlanNotFoundException(code));
    }

    @Override
    @Transactional
    public Plan createPlan(Plan plan) {
        plan.setId(UUID.randomUUID());
        plan.setCreatedAt(Instant.now());
        plan.setUpdatedAt(Instant.now());
        return planRepository.save(plan);
    }

    public Plan getDefaultPlanForProduct(String productCode) {
        return planRepository.findByProduct_CodeAndIsDefaultTrue(productCode)
                .orElseThrow(() -> new PlanNotFoundException("default for " + productCode));
    }
}