package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.entity.CreatorConnectorCategoryMap;
import com.dalai.llama.creator.domain.entity.CreatorConnectorCategoryMapId;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CreatorConnectorCategoryMapRepository extends JpaRepository<CreatorConnectorCategoryMap, CreatorConnectorCategoryMapId> {

    @EntityGraph(attributePaths = {"connector", "category"})
    @Query("""
            select mapping
            from CreatorConnectorCategoryMap mapping
            join mapping.connector connector
            join mapping.category category
            where mapping.enabled = true
              and connector.enabled = true
              and category.active = true
              and category.code = :categoryCode
            order by mapping.priority asc, connector.priority asc, connector.code asc
            """)
    List<CreatorConnectorCategoryMap> findRunnableMappingsForCategory(@Param("categoryCode") String categoryCode);
}
