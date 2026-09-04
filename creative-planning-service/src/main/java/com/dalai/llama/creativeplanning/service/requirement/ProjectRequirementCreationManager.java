package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.dto.CreateStandaloneRequirementRequest;
import com.dalai.llama.creativeplanning.dto.ProductProfileView;
import com.dalai.llama.creativeplanning.dto.ProjectRequirementView;
import com.dalai.llama.creativeplanning.service.BrandContextService;
import com.dalai.llama.creativeplanning.service.ProductProfileService;
import com.dalai.llama.creativeplanning.service.ProductReferenceImageService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates every step behind {@code POST /v1/project-requirements}'s multipart create:
 * <ol>
 *   <li>save the brief (which itself upserts an optional brand context -- see {@link
 *       ProjectRequirementService#createStandalone});</li>
 *   <li>store the optional reference image via the exact same path {@code POST
 *       .../{requirementId}/attachments} already uses;</li>
 *   <li>if {@code productDetails} was given, create a {@link ProductProfileView} tagged with this
 *       requirement (requires a brand context -- inline in the same request, or pre-existing) and
 *       store any {@code productImages} against it, unanalyzed;</li>
 *   <li>store any {@code projectReferenceImages} -- "what the client has in mind," distinct from
 *       the product images -- against the requirement, likewise unanalyzed;</li>
 *   <li>if the requirement came out already funded (a COMPANY tenant, funded at creation --
 *       see {@code ProjectRequirementService#build}), run {@link
 *       ReferenceMaterialAnalysisService} immediately, since no payment event will ever arrive to
 *       trigger it later.</li>
 * </ol>
 * Kept out of {@link ProjectRequirementService} itself because {@link
 * ProjectRequirementAttachmentService} already depends on {@code ProjectRequirementService} --
 * injecting it back would be a circular dependency. This manager sits above everything instead,
 * so the controller stays a thin adapter.
 */
@Service
public class ProjectRequirementCreationManager {

    private final ProjectRequirementService projectRequirementService;
    private final ProjectRequirementAttachmentService projectRequirementAttachmentService;
    private final ProjectReferenceImageService projectReferenceImageService;
    private final ReferenceMaterialAnalysisService referenceMaterialAnalysisService;
    private final BrandContextService brandContextService;
    private final ProductProfileService productProfileService;
    private final ProductReferenceImageService productReferenceImageService;

    public ProjectRequirementCreationManager(
            ProjectRequirementService projectRequirementService,
            ProjectRequirementAttachmentService projectRequirementAttachmentService,
            ProjectReferenceImageService projectReferenceImageService,
            ReferenceMaterialAnalysisService referenceMaterialAnalysisService,
            BrandContextService brandContextService,
            ProductProfileService productProfileService,
            ProductReferenceImageService productReferenceImageService
    ) {
        this.projectRequirementService = projectRequirementService;
        this.projectRequirementAttachmentService = projectRequirementAttachmentService;
        this.projectReferenceImageService = projectReferenceImageService;
        this.referenceMaterialAnalysisService = referenceMaterialAnalysisService;
        this.brandContextService = brandContextService;
        this.productProfileService = productProfileService;
        this.productReferenceImageService = productReferenceImageService;
    }

    public ProjectRequirementView createStandalone(
            UUID tenantId, UUID userId, CreateStandaloneRequirementRequest request,
            MultipartFile image, List<MultipartFile> productImages, List<MultipartFile> projectReferenceImages) {
        ProjectRequirementView requirement = projectRequirementService.createStandalone(tenantId, userId, request);

        if (isPresent(image)) {
            projectRequirementAttachmentService.upload(tenantId, requirement.id(), image);
        }

        if (request.productDetails() != null) {
            if (requirement.brandContextId() == null) {
                throw com.dalai.llama.creativeplanning.service.CreativePlanningException.badRequest(
                        "A brand is required before adding a product");
            }
            BrandContext brand = brandContextService.requireBrand(tenantId, requirement.brandContextId());
            ProductProfileView product = productProfileService.createForRequirement(
                    tenantId, requirement.id(), brand, request.productDetails());
            for (MultipartFile file : orEmpty(productImages)) {
                if (isPresent(file)) {
                    productReferenceImageService.storeWithoutAnalysis(tenantId, product.id(), file);
                }
            }
        }

        for (MultipartFile file : orEmpty(projectReferenceImages)) {
            if (isPresent(file)) {
                projectReferenceImageService.store(tenantId, requirement.id(), file);
            }
        }

        if (requirement.funded()) {
            referenceMaterialAnalysisService.analyzePendingForRequirement(tenantId, requirement.id());
        }

        return requirement;
    }

    private static boolean isPresent(MultipartFile file) {
        return file != null && !file.isEmpty();
    }

    private static List<MultipartFile> orEmpty(List<MultipartFile> files) {
        return files == null ? List.of() : files;
    }
}
