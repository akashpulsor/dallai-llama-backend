package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorTrendCombination;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CreatorTrendCombinationRepository extends JpaRepository<CreatorTrendCombination, UUID> {

    List<CreatorTrendCombination> findByEnabledTrueOrderByPlatformCodeAscCategoryCodeAsc();
}
