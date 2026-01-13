package com.dalai.llama.product.service;



import com.dalai.llama.product.domain.entity.Plan;

import java.util.List;

public interface PlanService {

    List<Plan> getAllPlans();

    List<Plan> getPlansByProductCode(String productCode);

    Plan getPlanByCode(String code);

    Plan createPlan(Plan plan);
}
