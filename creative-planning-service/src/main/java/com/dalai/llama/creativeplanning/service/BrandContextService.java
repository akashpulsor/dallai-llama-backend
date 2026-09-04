package com.dalai.llama.creativeplanning.service;

import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.domain.entity.BrandContextVersion;
import com.dalai.llama.creativeplanning.dto.BrandContextVersionView;
import com.dalai.llama.creativeplanning.dto.BrandContextView;
import com.dalai.llama.creativeplanning.dto.CreateBrandContextRequest;
import com.dalai.llama.creativeplanning.repository.BrandContextRepository;
import com.dalai.llama.creativeplanning.repository.BrandContextVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** A tenant can manage several brands (see {@link BrandContext}'s javadoc) -- every method here
 * is scoped by an explicit {@code brandId}, never "the tenant's one brand." Every save to an
 * existing brand also snapshots into {@link BrandContextVersion} so the Brands tab can show a
 * Google-Docs-style history of how the brand's own description changed over time. */
@Service
public class BrandContextService {

    private final BrandContextRepository brandContextRepository;
    private final BrandContextVersionRepository brandContextVersionRepository;

    public BrandContextService(BrandContextRepository brandContextRepository, BrandContextVersionRepository brandContextVersionRepository) {
        this.brandContextRepository = brandContextRepository;
        this.brandContextVersionRepository = brandContextVersionRepository;
    }

    /** Always creates a brand-new row -- see {@link #update} for editing an existing one. */
    @Transactional
    public BrandContextView create(UUID tenantId, CreateBrandContextRequest request) {
        OffsetDateTime now = OffsetDateTime.now();
        BrandContext brand = BrandContext.builder()
                .tenantId(tenantId)
                .brandName(request.brandName())
                .industry(request.industry())
                .brandVoice(request.brandVoice())
                .targetAudience(request.targetAudience())
                .brandValues(request.brandValues())
                .createdAt(now)
                .updatedAt(now)
                .build();
        brand = brandContextRepository.save(brand);
        snapshotVersion(brand, now);
        return toView(brand, 1);
    }

    /** Edits an existing brand in place and snapshots a new version -- the brand's id (and every
     * product/session/plan/requirement that already points at it) never changes. */
    @Transactional
    public BrandContextView update(UUID tenantId, UUID brandId, CreateBrandContextRequest request) {
        BrandContext brand = requireBrand(tenantId, brandId);
        OffsetDateTime now = OffsetDateTime.now();
        brand.setBrandName(request.brandName());
        brand.setIndustry(request.industry());
        brand.setBrandVoice(request.brandVoice());
        brand.setTargetAudience(request.targetAudience());
        brand.setBrandValues(request.brandValues());
        brand.setUpdatedAt(now);
        brand = brandContextRepository.save(brand);

        int nextVersion = snapshotVersion(brand, now);
        return toView(brand, nextVersion);
    }

    @Transactional(readOnly = true)
    public BrandContextView get(UUID tenantId, UUID brandId) {
        return toViewWithCurrentVersion(requireBrand(tenantId, brandId));
    }

    /** Every brand this tenant manages, newest first. */
    @Transactional(readOnly = true)
    public List<BrandContextView> list(UUID tenantId) {
        return brandContextRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(this::toViewWithCurrentVersion)
                .collect(Collectors.toList());
    }

    public BrandContext requireBrand(UUID tenantId, UUID brandId) {
        return brandContextRepository.findByIdAndTenantId(brandId, tenantId)
                .orElseThrow(() -> CreativePlanningException.notFound("No brand " + brandId + " for this tenant"));
    }

    /** Nullable variant for callers where a brand is optional grounding context rather than a
     * hard requirement -- e.g. a standalone requirement's reference-image analysis, which may
     * have no brand at all. */
    @Transactional(readOnly = true)
    public BrandContext findBrand(UUID tenantId, UUID brandId) {
        if (brandId == null) {
            return null;
        }
        return brandContextRepository.findByIdAndTenantId(brandId, tenantId).orElse(null);
    }

    /** Same nullable lookup as {@link #findBrand}, mapped to the view -- for callers (e.g. the
     * public brief page) that display a brand rather than feed it to a prompt. */
    @Transactional(readOnly = true)
    public BrandContextView findBrandView(UUID tenantId, UUID brandId) {
        BrandContext brand = findBrand(tenantId, brandId);
        return brand == null ? null : toViewWithCurrentVersion(brand);
    }

    /** One specific version's content -- what "previous"/"next version" navigation on the Brands
     * tab reads. */
    @Transactional(readOnly = true)
    public BrandContextVersionView getVersion(UUID tenantId, UUID brandId, Integer version) {
        requireBrand(tenantId, brandId); // ownership check
        BrandContextVersion v = brandContextVersionRepository.findByBrandContextIdAndVersion(brandId, version)
                .orElseThrow(() -> CreativePlanningException.notFound("No version " + version + " for brand " + brandId));
        return toVersionView(v);
    }

    /** Every version ever saved for this brand, oldest first -- populates a version picker. */
    @Transactional(readOnly = true)
    public List<BrandContextVersionView> listVersions(UUID tenantId, UUID brandId) {
        requireBrand(tenantId, brandId); // ownership check
        return brandContextVersionRepository.findByBrandContextIdOrderByVersionAsc(brandId).stream()
                .map(this::toVersionView)
                .collect(Collectors.toList());
    }

    private int snapshotVersion(BrandContext brand, OffsetDateTime now) {
        int nextVersion = brandContextVersionRepository.findTopByBrandContextIdOrderByVersionDesc(brand.getId())
                .map(v -> v.getVersion() + 1)
                .orElse(1);
        brandContextVersionRepository.save(BrandContextVersion.builder()
                .tenantId(brand.getTenantId())
                .brandContextId(brand.getId())
                .version(nextVersion)
                .brandName(brand.getBrandName())
                .industry(brand.getIndustry())
                .brandVoice(brand.getBrandVoice())
                .targetAudience(brand.getTargetAudience())
                .brandValues(brand.getBrandValues())
                .createdAt(now)
                .build());
        return nextVersion;
    }

    private BrandContextVersionView toVersionView(BrandContextVersion v) {
        return new BrandContextVersionView(v.getId(), v.getBrandContextId(), v.getVersion(), v.getBrandName(),
                v.getIndustry(), v.getBrandVoice(), v.getTargetAudience(), v.getBrandValues(), v.getCreatedAt());
    }

    private BrandContextView toViewWithCurrentVersion(BrandContext brand) {
        int currentVersion = brandContextVersionRepository.findTopByBrandContextIdOrderByVersionDesc(brand.getId())
                .map(BrandContextVersion::getVersion)
                .orElse(1);
        return toView(brand, currentVersion);
    }

    private BrandContextView toView(BrandContext brand, Integer currentVersion) {
        return new BrandContextView(brand.getId(), brand.getBrandName(), brand.getIndustry(),
                brand.getBrandVoice(), brand.getTargetAudience(), brand.getBrandValues(), brand.getCreatedAt(), currentVersion);
    }
}
