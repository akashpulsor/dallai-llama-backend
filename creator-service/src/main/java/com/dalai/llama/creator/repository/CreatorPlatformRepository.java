package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorPlatform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorPlatformRepository extends JpaRepository<CreatorPlatform, UUID> {

    List<CreatorPlatform> findByActiveTrueAndVisibleTrueAndTargetPlatformTrueOrderBySortOrderAscDisplayNameAsc();

    Optional<CreatorPlatform> findByCode(String code);
}
