package com.dalai.llama.postprod.repository;

import com.dalai.llama.postprod.domain.entity.ShotFrameExtraction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShotFrameExtractionRepository extends JpaRepository<ShotFrameExtraction, UUID> {

    Optional<ShotFrameExtraction> findByRequestIdAndTenantId(UUID requestId, UUID tenantId);
}
