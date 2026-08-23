package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.MotionGraphicPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MotionGraphicPlanRepository extends JpaRepository<MotionGraphicPlan, UUID> {

    Optional<MotionGraphicPlan> findByShotId(UUID shotId);
}
