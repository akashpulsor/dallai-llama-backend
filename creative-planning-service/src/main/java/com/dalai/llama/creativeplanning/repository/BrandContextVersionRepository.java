package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.BrandContextVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BrandContextVersionRepository extends JpaRepository<BrandContextVersion, UUID> {

    Optional<BrandContextVersion> findTopByBrandContextIdOrderByVersionDesc(UUID brandContextId);

    Optional<BrandContextVersion> findByBrandContextIdAndVersion(UUID brandContextId, Integer version);

    List<BrandContextVersion> findByBrandContextIdOrderByVersionAsc(UUID brandContextId);
}
