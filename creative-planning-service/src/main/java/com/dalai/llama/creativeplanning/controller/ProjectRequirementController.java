package com.dalai.llama.creativeplanning.controller;

import com.dalai.llama.creativeplanning.dto.CreateRequirementFromIdeaRequest;
import com.dalai.llama.creativeplanning.dto.CreateStandaloneRequirementRequest;
import com.dalai.llama.creativeplanning.dto.IdeaOptionView;
import com.dalai.llama.creativeplanning.dto.LockIdeaOptionRequest;
import com.dalai.llama.creativeplanning.dto.LockIdeaOptionResponse;
import com.dalai.llama.creativeplanning.dto.ProjectRequirementAttachmentView;
import com.dalai.llama.creativeplanning.dto.ProjectRequirementView;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementAttachmentService;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementIdeaService;
import com.dalai.llama.creativeplanning.service.requirement.ProjectRequirementService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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

    public ProjectRequirementController(
            ProjectRequirementService projectRequirementService,
            ProjectRequirementAttachmentService projectRequirementAttachmentService,
            ProjectRequirementIdeaService projectRequirementIdeaService
    ) {
        this.projectRequirementService = projectRequirementService;
        this.projectRequirementAttachmentService = projectRequirementAttachmentService;
        this.projectRequirementIdeaService = projectRequirementIdeaService;
    }

    @PostMapping("/v1/project-requirements/from-locked-idea/{lockedIdeaId}")
    public ResponseEntity<ProjectRequirementView> createFromLockedIdea(
            @PathVariable UUID lockedIdeaId, @Valid @RequestBody CreateRequirementFromIdeaRequest request) {
        return ResponseEntity.ok(projectRequirementService.createFromLockedIdea(
                tenant().tenantId(), tenant().userId(), lockedIdeaId, request));
    }

    @PostMapping("/v1/project-requirements")
    public ResponseEntity<ProjectRequirementView> createStandalone(@Valid @RequestBody CreateStandaloneRequirementRequest request) {
        return ResponseEntity.ok(projectRequirementService.createStandalone(tenant().tenantId(), tenant().userId(), request));
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
