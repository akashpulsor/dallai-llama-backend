package com.dalai.llama.videogen.repository;

import com.dalai.llama.videogen.domain.entity.ShotGenerationPlanCoverage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ShotGenerationPlanCoverageRepository extends JpaRepository<ShotGenerationPlanCoverage, Long> {

    List<ShotGenerationPlanCoverage> findByPlanIdOrderByOrdinalAsc(UUID planId);

    @Modifying
    @Query("delete from ShotGenerationPlanCoverage c where c.planId = :planId")
    void deleteByPlanId(@Param("planId") UUID planId);
}
