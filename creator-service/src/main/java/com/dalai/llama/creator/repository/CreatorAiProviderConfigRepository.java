package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorAiProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorAiProviderConfigRepository extends JpaRepository<CreatorAiProviderConfig, UUID> {

    List<CreatorAiProviderConfig> findByActiveTrueAndVisibleTrueOrderBySortOrderAscDisplayNameAsc();

    Optional<CreatorAiProviderConfig> findByCode(String code);

    Optional<CreatorAiProviderConfig> findFirstByActiveTrueAndDefaultProviderTrueOrderBySortOrderAsc();
}
