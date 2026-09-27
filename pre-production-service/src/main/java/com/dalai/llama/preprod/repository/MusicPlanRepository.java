package com.dalai.llama.preprod.repository;

import com.dalai.llama.preprod.domain.entity.MusicPlanRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MusicPlanRepository extends JpaRepository<MusicPlanRecord, UUID> {

    Optional<MusicPlanRecord> findByProjectIdAndTenantId(UUID projectId, UUID tenantId);
}
