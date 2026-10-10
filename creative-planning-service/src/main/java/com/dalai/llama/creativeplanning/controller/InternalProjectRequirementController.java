package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.CreateStandaloneRequirementRequest;
import com.dalai.llama.creativeplanning.dto.ProjectRequirementView;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** tenant-service -> here, when a creator turns a brand's request from their public profile into
 * a brief (Creator Showcase). Same {@code createStandalone} as the creator's own multipart route,
 * minus the files, under the internal no-JWT convention of {@link InternalNextBriefController};
 * the tenant comes from the path. Returns just what the caller needs: the requirement id and the
 * share token of the brief page the brand is sent to. */
@RestController
public class InternalProjectRequirementController {

    private final ProjectRequirementService projectRequirementService;

    public InternalProjectRequirementController(ProjectRequirementService projectRequirementService) {
        this.projectRequirementService = projectRequirementService;
    }

    public record CreatedBrief(UUID requirementId, String shareToken) {
    }

    @PostMapping("/api/v1/internal/tenants/{tenantId}/project-requirements/standalone")
    public ResponseEntity<CreatedBrief> createStandalone(@PathVariable UUID tenantId,
                                                         @Valid @RequestBody CreateStandaloneRequirementRequest request) {
        ProjectRequirementView created = projectRequirementService.createStandalone(tenantId, null, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedBrief(created.id(), created.shareToken()));
    }
}
