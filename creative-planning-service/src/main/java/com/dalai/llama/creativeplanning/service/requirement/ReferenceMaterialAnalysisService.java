package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.domain.entity.ProductProfile;
import com.dalai.llama.creativeplanning.domain.entity.ProductReferenceImage;
import com.dalai.llama.creativeplanning.domain.entity.ProjectReferenceImage;
import com.dalai.llama.creativeplanning.domain.entity.ProjectRequirement;
import com.dalai.llama.creativeplanning.dto.ReferenceImageAnalysisView;
import com.dalai.llama.creativeplanning.repository.ProductProfileRepository;
import com.dalai.llama.creativeplanning.repository.ProductReferenceImageRepository;
import com.dalai.llama.creativeplanning.repository.ProjectReferenceImageRepository;
import com.dalai.llama.creativeplanning.repository.ProjectRequirementRepository;
import com.dalai.llama.creativeplanning.service.BrandContextService;
import com.dalai.llama.creativeplanning.service.ProjectReferenceImageAnalysisService;
import com.dalai.llama.creativeplanning.service.ReferenceImageAnalysisService;
import com.dalai.llama.creativeplanning.service.storage.MinioObjectStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Runs the vision analysis for a standalone requirement's reference material -- both the product
 * images (if a product was created inline, see {@code ProductProfileService#createForRequirement})
 * and the project reference images (what the client has in mind) -- once, and only once, the
 * requirement is actually funded. Deferred rather than analyzed at upload time (unlike the
 * existing brand/campaign journey's {@code ProductReferenceImageService#upload}) because a
 * standalone brief's images are only worth the LLM cost once someone has actually paid for the
 * project; see {@code ProjectRequirementService#applyFunding} and {@code
 * ProjectRequirementCreationManager} (the COMPANY-tenant-funded-at-creation path) for the two
 * places this gets called from.
 * <p>
 * Idempotent per image: each image is skipped if it already has an analysis row, so a redelivered
 * {@code billing.payment.received} Kafka message (or a second call from a wallet-funded creation)
 * never re-runs (and re-bills) an already-analyzed image.
 */
@Slf4j
@Service
public class ReferenceMaterialAnalysisService {

    private final ProductProfileRepository productProfileRepository;
    private final ProductReferenceImageRepository productReferenceImageRepository;
    private final ProjectReferenceImageRepository projectReferenceImageRepository;
    private final ProjectRequirementRepository projectRequirementRepository;
    private final ReferenceImageAnalysisService referenceImageAnalysisService;
    private final ProjectReferenceImageAnalysisService projectReferenceImageAnalysisService;
    private final BrandContextService brandContextService;
    private final MinioObjectStorage minioObjectStorage;

    public ReferenceMaterialAnalysisService(
            ProductProfileRepository productProfileRepository,
            ProductReferenceImageRepository productReferenceImageRepository,
            ProjectReferenceImageRepository projectReferenceImageRepository,
            ProjectRequirementRepository projectRequirementRepository,
            ReferenceImageAnalysisService referenceImageAnalysisService,
            ProjectReferenceImageAnalysisService projectReferenceImageAnalysisService,
            BrandContextService brandContextService,
            MinioObjectStorage minioObjectStorage
    ) {
        this.productProfileRepository = productProfileRepository;
        this.productReferenceImageRepository = productReferenceImageRepository;
        this.projectReferenceImageRepository = projectReferenceImageRepository;
        this.projectRequirementRepository = projectRequirementRepository;
        this.referenceImageAnalysisService = referenceImageAnalysisService;
        this.projectReferenceImageAnalysisService = projectReferenceImageAnalysisService;
        this.brandContextService = brandContextService;
        this.minioObjectStorage = minioObjectStorage;
    }

    /** {@code @Async}: confirmed live this was blocking the payment-verification request/response
     * -- one sequential vision-LLM call per reference image, easily exceeding the route timeout
     * for a brief with more than one or two images. The caller (payment verification) only needs
     * the funding transition itself to have committed, not this; this is best-effort background
     * work that's already idempotent per image (see class javadoc), so firing it off detached is
     * safe -- a crash mid-analysis just leaves some images unanalyzed until the next trigger. */
    @Async
    @Transactional
    public void analyzePendingForRequirement(UUID tenantId, UUID requirementId) {
        UUID brandContextId = projectRequirementRepository.findByIdAndTenantId(requirementId, tenantId)
                .map(ProjectRequirement::getBrandContextId)
                .orElse(null);
        BrandContext brand = brandContextService.findBrand(tenantId, brandContextId);

        productProfileRepository.findByProjectRequirementId(requirementId).ifPresent(product ->
                analyzeProductImages(tenantId, product, brand));

        List<ProjectReferenceImage> projectImages = projectReferenceImageRepository.findByProjectRequirementId(requirementId);
        for (ProjectReferenceImage image : projectImages) {
            if (projectReferenceImageAnalysisService.hasAnalysis(image.getId())) {
                continue;
            }
            try {
                projectReferenceImageAnalysisService.analyze(tenantId, image.getId(), dataUri(image.getObjectKey()), brand);
            } catch (Exception ex) {
                log.error("Project reference image analysis failed for image {} (requirement {}): {}",
                        image.getId(), requirementId, ex.getMessage(), ex);
            }
        }
    }

    private void analyzeProductImages(UUID tenantId, ProductProfile product, BrandContext brand) {
        List<ProductReferenceImage> images = productReferenceImageRepository.findByProductProfileId(product.getId());
        for (ProductReferenceImage image : images) {
            if (referenceImageAnalysisService.getIfPresent(image.getId()) != null) {
                continue;
            }
            try {
                referenceImageAnalysisService.analyze(tenantId, image.getId(), dataUri(image.getObjectKey()), brand);
            } catch (Exception ex) {
                log.error("Product reference image analysis failed for image {} (product {}): {}",
                        image.getId(), product.getId(), ex.getMessage(), ex);
            }
        }
    }

    /** What {@code ProjectRequirementIdeaService#generateOptions} feeds the idea-generation
     * prompt as {@code {{referenceImageAnalysis}}} -- every analyzed product and project
     * reference image's description/style/mood, so generated ideas reflect what the images
     * actually show instead of only the brief's own text. Images without an analysis yet (still
     * pending, or the requirement isn't funded) are silently skipped rather than blocking idea
     * generation on them. */
    @Transactional(readOnly = true)
    public String summarizeForRequirement(UUID tenantId, UUID requirementId) {
        List<String> summaries = new ArrayList<>();

        productProfileRepository.findByProjectRequirementId(requirementId).ifPresent(product -> {
            List<ProductReferenceImage> images = productReferenceImageRepository.findByProductProfileId(product.getId());
            for (int i = 0; i < images.size(); i++) {
                ReferenceImageAnalysisView analysis = referenceImageAnalysisService.getIfPresent(images.get(i).getId());
                if (analysis != null) {
                    summaries.add("Product reference image %d: %s".formatted(i + 1, describe(analysis)));
                }
            }
        });

        List<ProjectReferenceImage> projectImages = projectReferenceImageRepository.findByProjectRequirementId(requirementId);
        for (int i = 0; i < projectImages.size(); i++) {
            ReferenceImageAnalysisView analysis = projectReferenceImageAnalysisService.getIfPresent(projectImages.get(i).getId());
            if (analysis != null) {
                summaries.add("Client reference image %d (what the client has in mind): %s".formatted(i + 1, describe(analysis)));
            }
        }

        return summaries.isEmpty() ? "(no reference images analyzed)" : String.join("\n", summaries);
    }

    private String describe(ReferenceImageAnalysisView analysis) {
        return "%s Style: %s. Colors: %s. Subject: %s. Suggested use: %s.".formatted(
                analysis.description(), analysis.styleNotes(), analysis.dominantColors(),
                analysis.subjectMatter(), analysis.suggestedUseCase());
    }

    /** Object keys are always written as {@code .../<uuid>.<extension>} (see {@code
     * UploadValidation#extensionFor}), so the extension reliably reconstructs the original
     * content type -- neither image table persists it separately. */
    private String dataUri(String objectKey) {
        String extension = objectKey.substring(objectKey.lastIndexOf('.') + 1);
        byte[] bytes = minioObjectStorage.downloadBytes(objectKey);
        return "data:image/" + extension + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }
}
