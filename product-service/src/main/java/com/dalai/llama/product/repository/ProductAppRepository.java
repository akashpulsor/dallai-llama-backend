package com.dalai.llama.product.repository;

import com.dalai.llama.product.domain.entity.ProductApp;
import com.dalai.llama.product.domain.entity.enums.AppType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductAppRepository extends JpaRepository<ProductApp, UUID> {


    @Query("SELECT pa FROM ProductApp pa WHERE pa.product.code = :productCode AND pa.enabled = true ORDER BY pa.displayOrder ASC")
    List<ProductApp> findEnabledAppsByProductCode(@Param("productCode") String productCode);

    boolean existsByProductIdAndAppType(UUID productId, AppType appType);

    /**
     * Find all enabled apps for a product, ordered by display order.
     * This is the MAIN query used by EntitlementService.
     */
    List<ProductApp> findByProductIdAndEnabledTrueOrderByDisplayOrderAsc(UUID productId);

    /**
     * Find all apps for a product (including disabled).
     */
    List<ProductApp> findByProductIdOrderByDisplayOrderAsc(UUID productId);

    /**
     * Find a specific app by product and type.
     */
    Optional<ProductApp> findByProductIdAndAppType(UUID productId, AppType appType);

    /**
     * Find apps by product code.
     */
    List<ProductApp> findByProduct_CodeAndEnabledTrueOrderByDisplayOrderAsc(String productCode);
}
