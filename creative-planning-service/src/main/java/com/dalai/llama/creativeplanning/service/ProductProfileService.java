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
        if (request.brandContextId() == null) {
            throw CreativePlanningException.badRequest("brandContextId is required");
        }
        BrandContext brand = brandContextService.requireBrand(tenantId, request.brandContextId());
        return toView(save(tenantId, brand.getId(), null, request));
    }

    /** Entry point B's inline product creation -- see {@code
     * ProjectRequirementCreationManager}: a creator starting a standalone brief can describe their
     * product right there instead of going through the full brand/campaign journey first. Requires
     * a brand context to already exist (inline in the same request, or from an earlier call) --
     * {@code brand} must be resolved by the caller so a missing brand fails before any product
     * or image is persisted, not partway through. */
    @Transactional
    public ProductProfileView createForRequirement(UUID tenantId, UUID requirementId, BrandContext brand, CreateProductRequest request) {
        return toView(save(tenantId, brand.getId(), requirementId, request));
    }

    private ProductProfile save(UUID tenantId, UUID brandContextId, UUID projectRequirementId, CreateProductRequest request) {
        OffsetDateTime now = OffsetDateTime.now();
        return productProfileRepository.save(ProductProfile.builder()
                .tenantId(tenantId)
                .brandContextId(brandContextId)
                .projectRequirementId(projectRequirementId)
                .name(request.name())
                .description(request.description())
                .category(request.category())
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    /** Every product tagged to one specific brand. */
    @Transactional(readOnly = true)
    public List<ProductProfileView> list(UUID tenantId, UUID brandId) {
        brandContextService.requireBrand(tenantId, brandId); // ownership check
        return productProfileRepository.findByBrandContextId(brandId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    /** Null if this requirement has no inline product -- a standalone requirement doesn't require
     * one, see {@code CreateStandaloneRequirementRequest.productDetails}. */
    @Transactional(readOnly = true)
    public ProductProfileView findByRequirement(UUID tenantId, UUID requirementId) {
        return productProfileRepository.findByProjectRequirementId(requirementId)
                .filter(product -> product.getTenantId().equals(tenantId))
                .map(this::toView)
                .orElse(null);
    }

    /** Partial update -- each field applied only when given, same "don't force a full retype to
     * fix one line" convention as {@code ProjectRequirementService#updateFromClient}. */
    @Transactional
    public ProductProfileView update(UUID tenantId, UUID productId, String name, String description, String category) {
        ProductProfile product = requireProduct(tenantId, productId);
        if (name != null && !name.isBlank()) {
            product.setName(name);
        }
        if (description != null) {
            product.setDescription(description);
        }
        if (category != null) {
            product.setCategory(category);
        }
        product.setUpdatedAt(OffsetDateTime.now());
        return toView(productProfileRepository.save(product));
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
