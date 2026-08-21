package com.dalai.llama.creativeplanning.service;

import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.domain.entity.ProductProfile;
import com.dalai.llama.creativeplanning.dto.CreateProductRequest;
import com.dalai.llama.creativeplanning.dto.ProductProfileView;
import com.dalai.llama.creativeplanning.repository.ProductProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProductProfileService {

    private final ProductProfileRepository productProfileRepository;
    private final BrandContextService brandContextService;

    public ProductProfileService(ProductProfileRepository productProfileRepository, BrandContextService brandContextService) {
        this.productProfileRepository = productProfileRepository;
        this.brandContextService = brandContextService;
    }

    @Transactional
    public ProductProfileView create(UUID tenantId, CreateProductRequest request) {
        BrandContext brand = brandContextService.requireBrand(tenantId);
        OffsetDateTime now = OffsetDateTime.now();
        ProductProfile product = productProfileRepository.save(ProductProfile.builder()
                .tenantId(tenantId)
                .brandContextId(brand.getId())
                .name(request.name())
                .description(request.description())
                .category(request.category())
                .createdAt(now)
                .updatedAt(now)
                .build());
        return toView(product);
    }

    @Transactional(readOnly = true)
    public List<ProductProfileView> list(UUID tenantId) {
        BrandContext brand = brandContextService.requireBrand(tenantId);
        return productProfileRepository.findByBrandContextId(brand.getId()).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    public ProductProfile requireProduct(UUID tenantId, UUID productId) {
        return productProfileRepository.findByIdAndTenantId(productId, tenantId)
                .orElseThrow(() -> CreativePlanningException.notFound("No product " + productId));
    }

    private ProductProfileView toView(ProductProfile product) {
        return new ProductProfileView(product.getId(), product.getBrandContextId(), product.getName(),
                product.getDescription(), product.getCategory());
    }
}
