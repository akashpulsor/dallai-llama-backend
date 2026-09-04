package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.domain.entity.BrandContext;
import com.dalai.llama.creativeplanning.dto.CreateBrandContextRequest;
import com.dalai.llama.creativeplanning.dto.CreateProductRequest;
import com.dalai.llama.creativeplanning.dto.ProductProfileView;
import com.dalai.llama.creativeplanning.dto.ProductReferenceImageView;
import com.dalai.llama.creativeplanning.dto.ProjectReferenceImageView;
import com.dalai.llama.creativeplanning.dto.PublicProjectRequirementView;
import com.dalai.llama.creativeplanning.service.BrandContextService;
import com.dalai.llama.creativeplanning.service.ProductProfileService;
import com.dalai.llama.creativeplanning.service.ProductReferenceImageService;
import com.dalai.llama.creativeplanning.service.requirement.ProjectReferenceImageService;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Deliberately unauthenticated -- see {@code SecurityConfig}'s carve-out for {@code
 * /v1/public/**}. This is the actual "shareable brief page" mechanic: whoever holds the link
 * (the AI_VIDEO_CREATOR path's funder, who has no account here) reads the brief and confirms
 * payment through this path, not the tenant-authenticated one. Possession of the (unguessable,
 * time-boxed) share token is the authorization -- no tenant id, no JWT.
 */
@RestController
public class PublicProjectRequirementController {

    private final ProjectRequirementService projectRequirementService;
    private final BrandContextService brandContextService;
    private final ProductProfileService productProfileService;
    private final ProductReferenceImageService productReferenceImageService;
    private final ProjectReferenceImageService projectReferenceImageService;

    public PublicProjectRequirementController(
            ProjectRequirementService projectRequirementService,
            BrandContextService brandContextService,
            ProductProfileService productProfileService,
            ProductReferenceImageService productReferenceImageService,
            ProjectReferenceImageService projectReferenceImageService
    ) {
        this.projectRequirementService = projectRequirementService;
        this.brandContextService = brandContextService;
        this.productProfileService = productProfileService;
        this.productReferenceImageService = productReferenceImageService;
        this.projectReferenceImageService = projectReferenceImageService;
    }

    /** The full picture the creator saw while building this brief -- brand context, product, and
     * every reference image -- assembled here rather than in {@code ProjectRequirementService}
     * since those pieces live in other services it has no reason to depend on. */
    @GetMapping("/v1/public/project-requirements/{shareToken}")
    public ResponseEntity<PublicProjectRequirementView> get(@PathVariable String shareToken) {
        return ResponseEntity.ok(fullView(shareToken, projectRequirementService.getByShareToken(shareToken)));
    }

    /** Lets whoever holds the link fill in the whole brief themselves -- brief text, brand,
     * product, and reference images -- the creator may send a mostly-blank brief and have the
     * client (who, in the AI_VIDEO_CREATOR path this page serves, IS the brand/product owner)
     * complete it before paying. Every piece is optional and applied independently: {@code
     * data.brand()} upserts this tenant's one brand row (same {@link BrandContextService#upsert}
     * a direct call would use); {@code data.product()} updates the requirement's product if one
     * exists, otherwise creates it (requires a brand to already exist, inline in the same request
     * or from an earlier edit); {@code images}/{@code productImages} are stored the same
     * "analyzed once funded" way as images added at creation time (see {@code
     * ReferenceMaterialAnalysisService}). See {@code ProjectRequirementService#updateFromClient}
     * for why all of this is refused once funded. */
    @PatchMapping(path = "/v1/public/project-requirements/{shareToken}", consumes = "multipart/form-data")
    public ResponseEntity<PublicProjectRequirementView> updateFromClient(
            @PathVariable String shareToken,
            @RequestPart(value = "data", required = false) UpdateBriefFromClientRequest data,
            @RequestPart(value = "images", required = false) List<MultipartFile> images,
            @RequestPart(value = "productImages", required = false) List<MultipartFile> productImages) {
        PublicProjectRequirementView updated = projectRequirementService.updateFromClient(shareToken,
                data == null ? null : data.briefText(), data == null ? null : data.targetAudience(),
                data == null ? null : data.campaignDirection());

        ProjectRequirementService.RequirementIdentity id = projectRequirementService.identifyByShareToken(shareToken);
        UUID brandContextId = id.brandContextId();

        if (data != null && data.brand() != null && data.brand().brandName() != null && !data.brand().brandName().isBlank()) {
            UpdateBriefFromClientRequest.BrandFields b = data.brand();
            CreateBrandContextRequest brandRequest = new CreateBrandContextRequest(
                    b.brandName(), b.industry(), b.brandVoice(), b.targetAudience(), b.brandValues());
            if (brandContextId != null) {
                brandContextService.update(id.tenantId(), brandContextId, brandRequest);
            } else {
                brandContextId = projectRequirementService.attachNewBrand(id.tenantId(), id.requirementId(), brandRequest);
            }
        }

        UUID productId = applyProductEdit(id.tenantId(), id.requirementId(), brandContextId, data == null ? null : data.product());

        if (images != null && !images.isEmpty()) {
            for (MultipartFile image : images) {
                projectReferenceImageService.store(id.tenantId(), id.requirementId(), image);
            }
        }
        if (productImages != null && !productImages.isEmpty() && productId != null) {
            for (MultipartFile image : productImages) {
                productReferenceImageService.storeWithoutAnalysis(id.tenantId(), productId, image);
            }
        }
        return ResponseEntity.ok(fullView(shareToken, updated));
    }

    /** Returns the product id to attach images to, or null if there's still no product (no edit
     * given, or a fresh-product edit with a blank name -- silently skipped rather than erroring,
     * since a client adding photos before typing a product name is a reasonable sequence). */
    private UUID applyProductEdit(UUID tenantId, UUID requirementId, UUID brandContextId, UpdateBriefFromClientRequest.ProductFields fields) {
        ProductProfileView existing = productProfileService.findByRequirement(tenantId, requirementId);
        if (fields == null) {
            return existing == null ? null : existing.id();
        }
        if (existing != null) {
            return productProfileService.update(tenantId, existing.id(), fields.name(), fields.description(), fields.category()).id();
        }
        if (fields.name() == null || fields.name().isBlank()) {
            return null;
        }
        BrandContext brand = brandContextService.findBrand(tenantId, brandContextId);
        if (brand == null) {
            throw com.dalai.llama.creativeplanning.service.CreativePlanningException.badRequest(
                    "Add brand details before adding a product");
        }
        return productProfileService.createForRequirement(tenantId, requirementId, brand,
                new CreateProductRequest(fields.name(), fields.description(), fields.category(), null)).id();
    }

    public record UpdateBriefFromClientRequest(
            String briefText, String targetAudience, String campaignDirection,
            BrandFields brand, ProductFields product
    ) {
        public record BrandFields(String brandName, String industry, String brandVoice, String targetAudience, String brandValues) {}
        public record ProductFields(String name, String description, String category) {}
    }

    /** The full picture the creator saw while building this brief -- brand context, product, and
     * every reference image -- assembled here rather than in {@code ProjectRequirementService}
     * since those pieces live in other services it has no reason to depend on. */
    private PublicProjectRequirementView fullView(String shareToken, PublicProjectRequirementView base) {
        ProjectRequirementService.RequirementIdentity id = projectRequirementService.identifyByShareToken(shareToken);

        ProductProfileView product = productProfileService.findByRequirement(id.tenantId(), id.requirementId());
        List<ProductReferenceImageView> productImages = product == null
                ? List.of() : productReferenceImageService.list(id.tenantId(), product.id());

        return new PublicProjectRequirementView(
                base.briefText(), base.targetAudience(), base.campaignDirection(),
                base.durationSeconds(), base.languages(), base.quotedTotalPrice(), base.quotedCurrency(),
                base.requiredPaymentPercent(), base.requiredAmount(), base.funded(),
                brandContextService.findBrandView(id.tenantId(), id.brandContextId()), product, productImages,
                projectReferenceImageService.list(id.requirementId()));
    }

    /** Records a payment confirmation -- see {@code ProjectRequirementService}'s class javadoc.
     * The real target for this is a payment provider's webhook once one is integrated, not a
     * button this page's visitor clicks directly. Requires {@code X-Payment-Webhook-Secret} to
     * match the configured secret; with none configured (no payment gateway wired up yet), every
     * call is rejected -- the share token alone is deliberately not enough authorization to mark
     * something funded. This service never processes a payment itself. */
    @PostMapping("/v1/public/project-requirements/{shareToken}/mark-funded")
    public ResponseEntity<PublicProjectRequirementView> markFunded(
            @PathVariable String shareToken,
            @RequestHeader(value = "X-Payment-Webhook-Secret", required = false, defaultValue = "") String webhookSecret) {
        return ResponseEntity.ok(projectRequirementService.markFundedByShareToken(shareToken, webhookSecret, null));
    }

    /** The real "fund this brief" button: starts a Razorpay order for exactly the price already
     * quoted on this brief. See {@code ProjectRequirementService#startPayment}. */
    @PostMapping("/v1/public/project-requirements/{shareToken}/payment")
    public ResponseEntity<ProjectRequirementService.RequirementPaymentOrder> startPayment(@PathVariable String shareToken) {
        return ResponseEntity.ok(projectRequirementService.startPayment(shareToken));
    }

    /** Verifies the client's Razorpay checkout callback and funds the brief -- the same real,
     * signature-checked path {@code ClientReviewPage}'s lock payment already uses, not the
     * webhook-only {@link #markFunded} above. */
    @PostMapping("/v1/public/project-requirements/{shareToken}/payment/verify")
    public ResponseEntity<PublicProjectRequirementView> verifyPayment(
            @PathVariable String shareToken, @Valid @RequestBody VerifyPaymentRequest request) {
        return ResponseEntity.ok(projectRequirementService.verifyPayment(shareToken, request.paymentId(),
                request.gatewayOrderId(), request.gatewayPaymentId(), request.gatewaySignature()));
    }

    public record VerifyPaymentRequest(
            UUID paymentId,
            @NotBlank String gatewayOrderId,
            @NotBlank String gatewayPaymentId,
            @NotBlank String gatewaySignature) {}
}
