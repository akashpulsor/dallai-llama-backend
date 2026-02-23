package com.dalai.llama.product.repository;

import com.dalai.llama.product.domain.entity.PlanAiConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlanAiConfigRepository extends JpaRepository<PlanAiConfig, UUID> {

    Optional<PlanAiConfig> findByPlan_Id(UUID planId);

    Optional<PlanAiConfig> findByPlan_Code(String planCode);


    @Query("SELECT c FROM PlanAiConfig c " +
            "LEFT JOIN FETCH c.sttProvider " +
            "LEFT JOIN FETCH c.ttsProvider " +
            "LEFT JOIN FETCH c.llmProvider " +
            "WHERE c.plan.id = :planId")
    Optional<PlanAiConfig> findByPlanIdWithProviders(UUID planId);
}
