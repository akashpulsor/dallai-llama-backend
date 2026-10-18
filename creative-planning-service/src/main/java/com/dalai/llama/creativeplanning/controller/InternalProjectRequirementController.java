package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.CreateStandaloneRequirementRequest;
import com.dalai.llama.creativeplanning.dto.ProjectRequirementView;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementCreationManager;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/** tenant-service -> here, when a creator turns a brand's request from their public profile into
 * a brief (Creator Showcase). Same {@code createStandalone} as the creator's own multipart route,
 * minus the files, under the internal no-JWT convention of {@link InternalNextBriefController};
 * the tenant comes from the path. Returns just what the caller needs: the requirement id and the
 * share token of the brief page the brand is sent to. */
@RestController
public class InternalProjectRequirementController {

    private final ProjectRequirementCreationManager creationManager;

    public InternalProjectRequirementController(ProjectRequirementCreationManager creationManager) {
        this.creationManager = creationManager;
    }

    /** The new brief, and its price and upfront amount when it was priced at creation (else null). */
    public record CreatedBrief(UUID requirementId, String shareToken, java.math.BigDecimal totalPrice,
                               java.math.BigDecimal requiredAmount, String currency) {
        static CreatedBrief of(ProjectRequirementView created) {
            return new CreatedBrief(created.id(), created.shareToken(), created.quotedTotalPrice(), created.requiredAmount(),
                    created.quotedCurrency());
        }
    }

    /** Through the creation manager (not the bare service) so {@code productDetails} creates the product. */
    @PostMapping(path = "/api/v1/internal/tenants/{tenantId}/project-requirements/standalone", consumes = "application/json")
    public ResponseEntity<CreatedBrief> createStandalone(@PathVariable UUID tenantId,
                                                         @Valid @RequestBody CreateStandaloneRequirementRequest request) {
        ProjectRequirementView created = creationManager.createStandalone(tenantId, null, request, null, null, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(CreatedBrief.of(created));
    }

    /** Same, with the brand's product and reference images (a request a brand sent from a creator's page). */
    @PostMapping(path = "/api/v1/internal/tenants/{tenantId}/project-requirements/standalone", consumes = "multipart/form-data")
    public ResponseEntity<CreatedBrief> createStandaloneWithImages(
            @PathVariable UUID tenantId,
            @Valid @RequestPart("data") CreateStandaloneRequirementRequest request,
            @RequestPart(value = "productImages", required = false) List<MultipartFile> productImages,
            @RequestPart(value = "projectReferenceImages", required = false) List<MultipartFile> projectReferenceImages) {
        ProjectRequirementView created = creationManager.createStandalone(tenantId, null, request, null, productImages, projectReferenceImages);
        return ResponseEntity.status(HttpStatus.CREATED).body(CreatedBrief.of(created));
    }
}
