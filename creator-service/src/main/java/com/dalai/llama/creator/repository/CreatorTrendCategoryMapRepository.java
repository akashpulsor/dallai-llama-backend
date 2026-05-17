package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorTrendCategoryMap;
import com.dalai.llama.creator.domain.entity.CreatorTrendCategoryMapId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreatorTrendCategoryMapRepository extends JpaRepository<CreatorTrendCategoryMap, CreatorTrendCategoryMapId> {
}
