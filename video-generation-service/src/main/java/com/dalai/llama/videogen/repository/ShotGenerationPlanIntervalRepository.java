package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.ShotGenerationPlanInterval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ShotGenerationPlanIntervalRepository extends JpaRepository<ShotGenerationPlanInterval, Long> {

    List<ShotGenerationPlanInterval> findByPlanIdOrderByOrdinalAsc(UUID planId);

    @Modifying
    @Query("delete from ShotGenerationPlanInterval c where c.planId = :planId")
    void deleteByPlanId(@Param("planId") UUID planId);
}
