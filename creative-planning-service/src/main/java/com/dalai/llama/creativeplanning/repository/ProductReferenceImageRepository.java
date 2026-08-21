package com.dalai.llama.creativeplanning.repository;

import com.dalai.llama.creativeplanning.domain.entity.ProductReferenceImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductReferenceImageRepository extends JpaRepository<ProductReferenceImage, UUID> {

    List<ProductReferenceImage> findByProductProfileId(UUID productProfileId);

    Optional<ProductReferenceImage> findByIdAndTenantId(UUID id, UUID tenantId);
}
