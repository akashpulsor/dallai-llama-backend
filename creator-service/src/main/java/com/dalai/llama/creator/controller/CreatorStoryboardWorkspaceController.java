package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.request.WorkspaceChatRequest;
import com.dalai.llama.creator.dto.request.WorkspaceCheckpointRequest;
import com.dalai.llama.creator.dto.request.WorkspaceRevertRequest;
import com.dalai.llama.creator.dto.response.CreatorStoryboardWorkspaceResponse;
import com.dalai.llama.creator.dto.response.GenerationJobResponse;
import com.dalai.llama.creator.dto.response.WorkspaceCheckpointResponse;
import com.dalai.llama.creator.dto.response.WorkspaceChatTurnResponse;
import com.dalai.llama.creator.dto.response.WorkspaceMergeResponse;
import com.dalai.llama.creator.service.CreatorStoryboardWorkspaceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Fully additive: a sandboxed "workspace" chat brain for a script's
 * storyboard, sitting alongside (not replacing) {@link CreatorStoryboardController}'s
 * existing {@code /client-review*} endpoints, which are untouched by this feature.
 */
@RestController
@RequestMapping("/api/v1/creator/storyboards/scripts/{scriptId}/workspace")
public class CreatorStoryboardWorkspaceController {

    private final CreatorStoryboardWorkspaceService workspaceService;

    public CreatorStoryboardWorkspaceController(CreatorStoryboardWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @PostMapping
    public ResponseEntity<CreatorStoryboardWorkspaceResponse> open(
            @PathVariable UUID scriptId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.status(HttpStatus.CREATED).body(workspaceService.openWorkspace(scriptId, tenantId, userId));
    }

    @GetMapping
    public ResponseEntity<List<CreatorStoryboardWorkspaceResponse>> list(
            @PathVariable UUID scriptId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(workspaceService.listWorkspaces(scriptId, tenantId, userId));
    }

    @PostMapping("/{workspaceId}/chat")
    public ResponseEntity<WorkspaceChatTurnResponse> chat(
            @PathVariable UUID scriptId,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody WorkspaceChatRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(workspaceService.chat(workspaceId, request, tenantId, userId));
    }

    @PostMapping(value = "/{workspaceId}/shots/{shotNumber}/inspiration-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GenerationJobResponse> uploadInspirationImage(
            @PathVariable UUID scriptId,
            @PathVariable UUID workspaceId,
            @PathVariable int shotNumber,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "note", required = false) String note,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(workspaceService.uploadShotInspirationImage(workspaceId, shotNumber, file, note, tenantId, userId));
    }

    @PostMapping("/{workspaceId}/checkpoints")
    public ResponseEntity<WorkspaceCheckpointResponse> createCheckpoint(
            @PathVariable UUID scriptId,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody WorkspaceCheckpointRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(workspaceService.createCheckpoint(workspaceId, request.title(), tenantId, userId));
    }

    @GetMapping("/{workspaceId}/checkpoints")
    public ResponseEntity<List<WorkspaceCheckpointResponse>> listCheckpoints(
            @PathVariable UUID scriptId,
            @PathVariable UUID workspaceId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(workspaceService.listCheckpoints(workspaceId, tenantId, userId));
    }

    @PostMapping("/{workspaceId}/revert")
    public ResponseEntity<CreatorStoryboardWorkspaceResponse> revert(
            @PathVariable UUID scriptId,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody WorkspaceRevertRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(workspaceService.revert(workspaceId, request.toVersion(), tenantId, userId));
    }

    @PostMapping("/{workspaceId}/merge")
    public ResponseEntity<WorkspaceMergeResponse> merge(
            @PathVariable UUID scriptId,
            @PathVariable UUID workspaceId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(workspaceService.merge(workspaceId, tenantId, userId));
    }
}
