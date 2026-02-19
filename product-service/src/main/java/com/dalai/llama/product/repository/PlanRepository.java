package com.dalai.llama.product.repository;

import com.dalai.llama.product.domain.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanRepository extends JpaRepository<Plan, UUID> {

    Optional<Plan> findByCode(String code);

    List<Plan> findByProduct_CodeAndActiveTrue(String productCode);

    Optional<Plan> findByProduct_CodeAndIsDefaultTrue(String productCode);


}
