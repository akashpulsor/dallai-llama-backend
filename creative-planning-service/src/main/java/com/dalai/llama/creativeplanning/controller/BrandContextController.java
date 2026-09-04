package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.BrandContextVersionView;
import com.dalai.llama.creativeplanning.dto.BrandContextView;
import com.dalai.llama.creativeplanning.dto.CreateBrandContextRequest;
import com.dalai.llama.creativeplanning.dto.ProjectRequirementView;
import com.dalai.llama.creativeplanning.service.BrandContextService;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** A tenant can manage several brands (see {@code BrandContextService}'s javadoc) -- every route
 * here is scoped by an explicit brand id, a real REST resource rather than the old implicit
 * "the tenant's one brand" singleton. */
@RestController
public class BrandContextController extends BaseController {

    private final BrandContextService brandContextService;
    private final ProjectRequirementService projectRequirementService;

    public BrandContextController(BrandContextService brandContextService, ProjectRequirementService projectRequirementService) {
        this.brandContextService = brandContextService;
        this.projectRequirementService = projectRequirementService;
    }

    @PostMapping("/v1/brands")
    public ResponseEntity<BrandContextView> create(@Valid @RequestBody CreateBrandContextRequest request) {
        return ResponseEntity.ok(brandContextService.create(tenant().tenantId(), request));
    }

    @GetMapping("/v1/brands")
    public ResponseEntity<List<BrandContextView>> list() {
        return ResponseEntity.ok(brandContextService.list(tenant().tenantId()));
    }

    @GetMapping("/v1/brands/{brandId}")
    public ResponseEntity<BrandContextView> get(@PathVariable UUID brandId) {
        return ResponseEntity.ok(brandContextService.get(tenant().tenantId(), brandId));
    }

    /** Edits this brand in place and snapshots a new version -- see {@code
     * BrandContextService#update}. */
    @PutMapping("/v1/brands/{brandId}")
    public ResponseEntity<BrandContextView> update(@PathVariable UUID brandId, @Valid @RequestBody CreateBrandContextRequest request) {
        return ResponseEntity.ok(brandContextService.update(tenant().tenantId(), brandId, request));
    }

    /** Every version ever saved for this brand, oldest first -- backs version navigation on the
     * Brands tab. */
    @GetMapping("/v1/brands/{brandId}/versions")
    public ResponseEntity<List<BrandContextVersionView>> listVersions(@PathVariable UUID brandId) {
        return ResponseEntity.ok(brandContextService.listVersions(tenant().tenantId(), brandId));
    }

    /** One specific version -- what "previous"/"next version" reads. */
    @GetMapping("/v1/brands/{brandId}/versions/{version}")
    public ResponseEntity<BrandContextVersionView> getVersion(@PathVariable UUID brandId, @PathVariable Integer version) {
        return ResponseEntity.ok(brandContextService.getVersion(tenant().tenantId(), brandId, version));
    }

    /** Every brief/project created for this brand -- what the Brands tab reads to answer "what
     * has this brand actually been used for." */
    @GetMapping("/v1/brands/{brandId}/projects")
    public ResponseEntity<List<ProjectRequirementView>> projects(@PathVariable UUID brandId) {
        return ResponseEntity.ok(projectRequirementService.listByBrand(tenant().tenantId(), brandId));
    }
}
