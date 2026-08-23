package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.SaveScreenplayEditRequest;
import com.dalai.llama.preprod.dto.ScreenplayView;
import com.dalai.llama.preprod.service.ScreenplayGenerationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ScreenplayController extends BaseController {

    private final ScreenplayGenerationService screenplayGenerationService;

    public ScreenplayController(ScreenplayGenerationService screenplayGenerationService) {
        this.screenplayGenerationService = screenplayGenerationService;
    }

    /** Always creates a new version -- see ScreenplayGenerationService's class javadoc. */
    @PostMapping("/v1/projects/{projectId}/screenplay/generate")
    public ResponseEntity<ScreenplayView> generate(@PathVariable UUID projectId) {
        return ResponseEntity.ok(screenplayGenerationService.generate(tenant().tenantId(), projectId));
    }

    /** Latest version. */
    @GetMapping("/v1/projects/{projectId}/screenplay")
    public ResponseEntity<ScreenplayView> get(@PathVariable UUID projectId) {
        return ResponseEntity.ok(screenplayGenerationService.get(tenant().tenantId(), projectId));
    }

    /** Every version ever saved for this project, oldest first -- backs version navigation. */
    @GetMapping("/v1/projects/{projectId}/screenplay/versions")
    public ResponseEntity<List<ScreenplayView>> listVersions(@PathVariable UUID projectId) {
        return ResponseEntity.ok(screenplayGenerationService.listVersions(tenant().tenantId(), projectId));
    }

    /** One specific version -- what "previous"/"next version" reads. */
    @GetMapping("/v1/projects/{projectId}/screenplay/versions/{version}")
    public ResponseEntity<ScreenplayView> getVersion(@PathVariable UUID projectId, @PathVariable Integer version) {
        return ResponseEntity.ok(screenplayGenerationService.getVersion(tenant().tenantId(), projectId, version));
    }

    /** Saves a manual scene edit as a new EDITED version of {@code fromVersion} -- no LLM call. */
    @PostMapping("/v1/projects/{projectId}/screenplay/versions/{fromVersion}/edit")
    public ResponseEntity<ScreenplayView> saveEdit(
            @PathVariable UUID projectId, @PathVariable Integer fromVersion, @Valid @RequestBody SaveScreenplayEditRequest request) {
        return ResponseEntity.ok(screenplayGenerationService.saveEdit(tenant().tenantId(), projectId, fromVersion, request));
    }
}
