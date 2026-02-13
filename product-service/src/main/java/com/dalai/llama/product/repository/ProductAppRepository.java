package com.dalai.llama.product.repository;

import com.dalai.llama.product.domain.entity.ProductApp;
import com.dalai.llama.product.domain.entity.enums.AppType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductAppRepository extends JpaRepository<ProductApp, UUID> {

    List<ProductApp> findByProductIdAndEnabledTrueOrderByDisplayOrderAsc(UUID productId);

    Optional<ProductApp> findByProductIdAndAppType(UUID productId, AppType appType);

    @Query("SELECT pa FROM ProductApp pa WHERE pa.product.code = :productCode AND pa.enabled = true ORDER BY pa.displayOrder ASC")
    List<ProductApp> findEnabledAppsByProductCode(@Param("productCode") String productCode);

    boolean existsByProductIdAndAppType(UUID productId, AppType appType);
}
