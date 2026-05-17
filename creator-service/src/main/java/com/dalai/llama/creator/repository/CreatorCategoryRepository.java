package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorCategoryRepository extends JpaRepository<CreatorCategory, UUID> {

    List<CreatorCategory> findByActiveTrueAndVisibleTrueOrderBySortOrderAscDisplayNameAsc();

    List<CreatorCategory> findByActiveTrueOrderBySortOrderAscDisplayNameAsc();

    Optional<CreatorCategory> findByCode(String code);
}
