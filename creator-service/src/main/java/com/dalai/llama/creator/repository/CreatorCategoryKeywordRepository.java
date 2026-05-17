package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorCategoryKeyword;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CreatorCategoryKeywordRepository extends JpaRepository<CreatorCategoryKeyword, UUID> {

    @EntityGraph(attributePaths = "category")
    @Query("""
            select keyword
            from CreatorCategoryKeyword keyword
            join keyword.category category
            where keyword.active = true
              and category.active = true
            order by category.sortOrder asc, keyword.sourceType asc, keyword.locale asc
            """)
    List<CreatorCategoryKeyword> findActiveSchedulerKeywords();

    @EntityGraph(attributePaths = "category")
    @Query("""
            select keyword
            from CreatorCategoryKeyword keyword
            join keyword.category category
            where keyword.active = true
              and category.active = true
              and category.code = :categoryCode
              and keyword.sourceType = :sourceType
            order by keyword.weight desc, keyword.locale asc
            """)
    List<CreatorCategoryKeyword> findActiveByCategoryCodeAndSourceType(
            @Param("categoryCode") String categoryCode,
            @Param("sourceType") String sourceType
    );

    @EntityGraph(attributePaths = "category")
    @Query("""
            select keyword
            from CreatorCategoryKeyword keyword
            join keyword.category category
            where keyword.active = true
              and category.active = true
              and category.code = :categoryCode
            order by keyword.weight desc, keyword.sourceType asc, keyword.locale asc
            """)
    List<CreatorCategoryKeyword> findActiveByCategoryCode(@Param("categoryCode") String categoryCode);
}
