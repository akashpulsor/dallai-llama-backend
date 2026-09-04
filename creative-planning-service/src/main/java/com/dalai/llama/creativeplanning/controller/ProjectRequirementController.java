package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.CreateRequirementFromIdeaRequest;
import com.dalai.llama.creativeplanning.dto.CreateStandaloneRequirementRequest;
import com.dalai.llama.creativeplanning.dto.IdeaOptionView;
import com.dalai.llama.creativeplanning.dto.LockIdeaOptionRequest;
import com.dalai.llama.creativeplanning.dto.LockIdeaOptionResponse;
import com.dalai.llama.creativeplanning.dto.ProductProfileView;
import com.dalai.llama.creativeplanning.dto.ProductReferenceImageView;
import com.dalai.llama.creativeplanning.dto.ProjectReferenceImageView;
import com.dalai.llama.creativeplanning.dto.ProjectRequirementAttachmentView;
import com.dalai.llama.creativeplanning.dto.ProjectRequirementView;
import com.dalai.llama.creativeplanning.dto.UpdateRequirementQuoteRequest;
import com.dalai.llama.creativeplanning.service.ProductProfileService;
import com.dalai.llama.creativeplanning.service.ProductReferenceImageService;
import com.dalai.llama.creativeplanning.service.requirement.ProjectReferenceImageService;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementAttachmentService;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementCreationManager;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementIdeaService;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/** Both entry points (from a locked idea, or a standalone brief) converge on the same {@code
 * ProjectRequirement} resource and the same review/attach/fund path -- see {@code
 * ProjectRequirementService}'s class javadoc for the funding-state caveat: {@code mark-funded}
 * records a payment confirmation, it never processes one. */
@RestController
public class ProjectRequirementController extends BaseController {

    private final ProjectRequirementService projectRequirementService;
    private final ProjectRequirementAttachmentService projectRequirementAttachmentService;
    private final ProjectRequirementIdeaService projectRequirementIdeaService;
    private final ProjectRequirementCreationManager projectRequirementCreationManager;
    private final ProjectReferenceImageService projectReferenceImageService;
    private final ProductProfileService productProfileService;
    private final ProductReferenceImageService productReferenceImageService;

    public ProjectRequirementController(
            ProjectRequirementService projectRequirementService,
            ProjectRequirementAttachmentService projectRequirementAttachmentService,
            ProjectRequirementIdeaService projectRequirementIdeaService,
            ProjectRequirementCreationManager projectRequirementCreationManager,
            ProjectReferenceImageService projectReferenceImageService,
            ProductProfileService productProfileService,
            ProductReferenceImageService productReferenceImageService
    ) {
        this.projectRequirementService = projectRequirementService;
        this.projectRequirementAttachmentService = projectRequirementAttachmentService;
        this.projectRequirementIdeaService = projectRequirementIdeaService;
        this.projectRequirementCreationManager = projectRequirementCreationManager;
        this.projectReferenceImageService = projectReferenceImageService;
        this.productProfileService = productProfileService;
        this.productReferenceImageService = productReferenceImageService;
    }

    @PostMapping("/v1/project-requirements/from-locked-idea/{lockedIdeaId}")
    public ResponseEntity<ProjectRequirementView> createFromLockedIdea(
            @PathVariable UUID lockedIdeaId, @Valid @RequestBody CreateRequirementFromIdeaRequest request) {
        return ResponseEntity.ok(projectRequirementService.createFromLockedIdea(
                tenant().tenantId(), tenant().userId(), lockedIdeaId, request));
    }

    /** Multipart so reference material can ride along with creation itself, instead of a
     * create-then-upload round trip -- {@code data} is the JSON brief (optionally carrying a
     * {@code brandContext} and/or {@code productDetails}). {@code image} is the brief's own
     * reference attachment (same as {@link #uploadAttachment}); {@code productImages} are images
     * of the product named in {@code productDetails} (ignored if that's absent); {@code
     * projectReferenceImages} are "what the client has in mind" images, unrelated to any product.
     * All three are optional and, unlike {@link #uploadAttachment}, are stored but not yet
     * analyzed -- analysis is deferred until the requirement is funded, see {@code
     * ReferenceMaterialAnalysisService}. Every step is delegated to {@link
     * ProjectRequirementCreationManager} rather than orchestrated here. */
    @PostMapping(path = "/v1/project-requirements", consumes = "multipart/form-data")
    public ResponseEntity<ProjectRequirementView> createStandalone(
            @Valid @RequestPart("data") CreateStandaloneRequirementRequest request,
            @RequestPart(value = "image", required = false) MultipartFile image,
            @RequestPart(value = "productImages", required = false) List<MultipartFile> productImages,
            @RequestPart(value = "projectReferenceImages", required = false) List<MultipartFile> projectReferenceImages) {
        return ResponseEntity.ok(projectRequirementCreationManager.createStandalone(
                tenant().tenantId(), tenant().userId(), request, image, productImages, projectReferenceImages));
    }

    @GetMapping("/v1/project-requirements")
    public ResponseEntity<List<ProjectRequirementView>> list() {
        return ResponseEntity.ok(projectRequirementService.list(tenant().tenantId()));
    }

    @GetMapping("/v1/project-requirements/{requirementId}")
    public ResponseEntity<ProjectRequirementView> get(@PathVariable UUID requirementId) {
        return ResponseEntity.ok(projectRequirementService.get(tenant().tenantId(), requirementId));
    }

    /** Records a payment confirmation -- see the class javadoc. Not a "pay now" action. */
    @PostMapping("/v1/project-requirements/{requirementId}/mark-funded")
    public ResponseEntity<ProjectRequirementView> markFunded(@PathVariable UUID requirementId) {
        return ResponseEntity.ok(projectRequirementService.markFunded(tenant().tenantId(), requirementId, tenant().userId()));
    }

    /** A creator overriding their own auto-computed quote and/or configuring a partial payment --
     * see {@code ProjectRequirementService#updateQuote}. Creator-only (this is the tenant-
     * authenticated controller); the client only ever sees the resulting numbers via the public
     * share-link view. */
    @PatchMapping("/v1/project-requirements/{requirementId}/quote")
    public ResponseEntity<ProjectRequirementView> updateQuote(
            @PathVariable UUID requirementId, @Valid @RequestBody UpdateRequirementQuoteRequest request) {
        return ResponseEntity.ok(projectRequirementService.updateQuote(tenant().tenantId(), requirementId, request));
    }

    /** "The client hasn't paid yet and asked me to resend" -- issues a fresh link and expiry;
     * the old link stops working immediately. */
    @PostMapping("/v1/project-requirements/{requirementId}/refresh-share-token")
    public ResponseEntity<ProjectRequirementView> refreshShareToken(@PathVariable UUID requirementId) {
        return ResponseEntity.ok(projectRequirementService.refreshShareToken(tenant().tenantId(), requirementId));
    }

    @PostMapping(path = "/v1/project-requirements/{requirementId}/attachments", consumes = "multipart/form-data")
    public ResponseEntity<ProjectRequirementAttachmentView> uploadAttachment(
            @PathVariable UUID requirementId, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(projectRequirementAttachmentService.upload(tenant().tenantId(), requirementId, file));
    }

    @GetMapping("/v1/project-requirements/{requirementId}/attachments")
    public ResponseEntity<List<ProjectRequirementAttachmentView>> listAttachments(@PathVariable UUID requirementId) {
        return ResponseEntity.ok(projectRequirementAttachmentService.list(tenant().tenantId(), requirementId));
    }

    /** Null body if no product was created inline for this requirement (see {@code
     * productDetails} on the create request) -- a standalone requirement doesn't require one. */
    @GetMapping("/v1/project-requirements/{requirementId}/product")
    public ResponseEntity<ProductProfileView> getProduct(@PathVariable UUID requirementId) {
        return ResponseEntity.ok(productProfileService.findByRequirement(tenant().tenantId(), requirementId));
    }

    /** The product's own reference images (and their vision analysis, once funded -- see {@code
     * ReferenceMaterialAnalysisService}) -- empty if this requirement has no product. */
    @GetMapping("/v1/project-requirements/{requirementId}/product/reference-images")
    public ResponseEntity<List<ProductReferenceImageView>> listProductReferenceImages(@PathVariable UUID requirementId) {
        ProductProfileView product = productProfileService.findByRequirement(tenant().tenantId(), requirementId);
        if (product == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(productReferenceImageService.list(tenant().tenantId(), product.id()));
    }

    /** "What the client has in mind" images (and their vision analysis, once funded), distinct
     * from the product's own reference images above. */
    @GetMapping("/v1/project-requirements/{requirementId}/reference-images")
    public ResponseEntity<List<ProjectReferenceImageView>> listProjectReferenceImages(@PathVariable UUID requirementId) {
        return ResponseEntity.ok(projectReferenceImageService.list(requirementId));
    }

    /** Only callable once {@code funded} is true -- see ProjectRequirementIdeaService. Options
     * are generated fresh each call, not persisted, so this is safe to call again for a new set. */
    @PostMapping("/v1/project-requirements/{requirementId}/ideas/generate")
    public ResponseEntity<List<IdeaOptionView>> generateIdeaOptions(
            @PathVariable UUID requirementId,
            @RequestParam(required = false) Integer count) {
        return ResponseEntity.ok(projectRequirementIdeaService.generateOptions(tenant().tenantId(), requirementId, count));
    }

    /** What a refreshed page reads instead of losing the generated list -- every option ever
     * saved for this requirement, newest first. */
    @GetMapping("/v1/project-requirements/{requirementId}/ideas")
    public ResponseEntity<List<IdeaOptionView>> listIdeaOptions(@PathVariable UUID requirementId) {
        return ResponseEntity.ok(projectRequirementIdeaService.listOptions(tenant().tenantId(), requirementId));
    }

    /** Saves a creator's edit of an existing option as a new row (source=EDITED) rather than
     * overwriting the original -- see ProjectRequirementIdeaService's javadoc. */
    @PostMapping("/v1/project-requirements/{requirementId}/ideas/{optionId}/save-edit")
    public ResponseEntity<IdeaOptionView> saveEditedIdeaOption(
            @PathVariable UUID requirementId,
            @PathVariable UUID optionId,
            @Valid @RequestBody LockIdeaOptionRequest edited) {
        return ResponseEntity.ok(projectRequirementIdeaService.saveEditedOption(tenant().tenantId(), requirementId, optionId, edited));
    }

    /** Locks one of the generated options and synchronously hands it to pre-production-service --
     * this is the funded-brief -> pre-production transition. */
    @PostMapping("/v1/project-requirements/{requirementId}/ideas/lock")
    public ResponseEntity<LockIdeaOptionResponse> lockIdeaOption(
            @PathVariable UUID requirementId,
            @Valid @RequestBody LockIdeaOptionRequest request) {
        return ResponseEntity.ok(projectRequirementIdeaService.lockOption(tenant().tenantId(), requirementId, request));
    }
}
