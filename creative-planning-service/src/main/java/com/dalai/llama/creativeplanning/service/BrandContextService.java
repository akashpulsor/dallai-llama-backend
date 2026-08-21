package com.dalai.llama.creativeplanning.service;

import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.dto.BrandContextView;
import com.dalai.llama.creativeplanning.dto.CreateBrandContextRequest;
import com.dalai.llama.creativeplanning.repository.BrandContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One brand per tenant -- {@code upsert} both creates it the first time and edits it on every
 * later call, since a tenant only ever has one {@link BrandContext} row. */
@Service
public class BrandContextService {

    private final BrandContextRepository brandContextRepository;

    public BrandContextService(BrandContextRepository brandContextRepository) {
        this.brandContextRepository = brandContextRepository;
    }

    @Transactional
    public BrandContextView upsert(UUID tenantId, CreateBrandContextRequest request) {
        OffsetDateTime now = OffsetDateTime.now();
        BrandContext brand = brandContextRepository.findByTenantId(tenantId).orElseGet(() -> BrandContext.builder()
                .tenantId(tenantId)
                .createdAt(now)
                .build());
        brand.setBrandName(request.brandName());
        brand.setIndustry(request.industry());
        brand.setBrandVoice(request.brandVoice());
        brand.setTargetAudience(request.targetAudience());
        brand.setBrandValues(request.brandValues());
        brand.setUpdatedAt(now);
        return toView(brandContextRepository.save(brand));
    }

    @Transactional(readOnly = true)
    public BrandContextView get(UUID tenantId) {
        return toView(requireBrand(tenantId));
    }

    public BrandContext requireBrand(UUID tenantId) {
        return brandContextRepository.findByTenantId(tenantId)
                .orElseThrow(() -> CreativePlanningException.notFound("No brand context for this tenant yet"));
    }

    private BrandContextView toView(BrandContext brand) {
        return new BrandContextView(brand.getId(), brand.getBrandName(), brand.getIndustry(),
                brand.getBrandVoice(), brand.getTargetAudience(), brand.getBrandValues(), brand.getCreatedAt());
    }
}
