package com.dalai.llama.postprod.repository;

import com.dalai.llama.postprod.domain.entity.ShotClipConform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShotClipConformRepository extends JpaRepository<ShotClipConform, UUID> {

    Optional<ShotClipConform> findByRequestIdAndTenantId(UUID requestId, UUID tenantId);
}
