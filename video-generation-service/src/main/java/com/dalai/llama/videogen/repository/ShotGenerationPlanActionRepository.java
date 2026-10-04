package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.ShotGenerationPlanAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ShotGenerationPlanActionRepository extends JpaRepository<ShotGenerationPlanAction, Long> {

    List<ShotGenerationPlanAction> findByPlanIdOrderByOrdinalAsc(UUID planId);

    @Modifying
    @Query("delete from ShotGenerationPlanAction c where c.planId = :planId")
    void deleteByPlanId(@Param("planId") UUID planId);
}
