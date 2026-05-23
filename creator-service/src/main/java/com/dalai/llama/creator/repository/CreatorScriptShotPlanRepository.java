package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorScriptShotPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorScriptShotPlanRepository extends JpaRepository<CreatorScriptShotPlan, UUID> {

    List<CreatorScriptShotPlan> findByScriptIdOrderByShotNumberAsc(UUID scriptId);

    Optional<CreatorScriptShotPlan> findByScriptIdAndShotNumberAndStyleKey(UUID scriptId, Integer shotNumber, String styleKey);

    void deleteByScriptId(UUID scriptId);
}
