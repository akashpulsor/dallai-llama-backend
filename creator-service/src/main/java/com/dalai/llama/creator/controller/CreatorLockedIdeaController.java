package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.request.CharacterCastMappingRequest;
import com.dalai.llama.creator.dto.request.GenerateStoryIdeaScriptRequest;
import com.dalai.llama.creator.dto.request.GenerateStoryScriptRequest;
import com.dalai.llama.creator.dto.request.LockIdeaSelectionRequest;
import com.dalai.llama.creator.dto.request.SaveGeneratedScriptRequest;
import com.dalai.llama.creator.dto.request.SaveStoryScriptRequest;
import com.dalai.llama.creator.dto.response.CharacterCastMappingResponse;
import com.dalai.llama.creator.dto.response.GeneratedIdeaResponse;
import com.dalai.llama.creator.dto.response.GeneratedScriptResponse;
import com.dalai.llama.creator.dto.response.GeneratedStoryScriptResponse;
import com.dalai.llama.creator.dto.response.LockedIdeaSelectionResponse;
import com.dalai.llama.creator.service.CharacterCastMappingService;
import com.dalai.llama.creator.service.IdeaService;
import com.dalai.llama.creator.service.LockedIdeaSelectionService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/creator/locked-ideas")
public class CreatorLockedIdeaController {

    private final LockedIdeaSelectionService lockedIdeaSelectionService;
    private final IdeaService ideaService;
    private final CharacterCastMappingService characterCastMappingService;

    public CreatorLockedIdeaController(
            LockedIdeaSelectionService lockedIdeaSelectionService,
            IdeaService ideaService,
            CharacterCastMappingService characterCastMappingService
    ) {
        this.lockedIdeaSelectionService = lockedIdeaSelectionService;
        this.ideaService = ideaService;
        this.characterCastMappingService = characterCastMappingService;
    }

    @PostMapping("/selection")
    public ResponseEntity<LockedIdeaSelectionResponse> lockSelection(
            @Valid @RequestBody LockIdeaSelectionRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(lockedIdeaSelectionService.lockSelection(request, tenantId, userId));
    }

    @PostMapping("/{lockedIdeaId}/ideas/generate")
    public ResponseEntity<Page<GeneratedIdeaResponse>> generateIdeaCandidates(
            @PathVariable UUID lockedIdeaId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication,
            @PageableDefault(size = 5) Pageable pageable
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(ideaService.generateIdeasForLockedBrief(lockedIdeaId, tenantId, userId, pageable));
    }

    @PostMapping("/{lockedIdeaId}/story-ideas/{storyIdeaId}/save")
    public ResponseEntity<GeneratedIdeaResponse> saveStoryIdea(
            @PathVariable UUID lockedIdeaId,
            @PathVariable UUID storyIdeaId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(ideaService.saveStoryIdea(lockedIdeaId, storyIdeaId, tenantId, userId));
    }

    @PostMapping("/{lockedIdeaId}/story-ideas/{storyIdeaId}/script/generate")
    public ResponseEntity<GeneratedStoryScriptResponse> generateStoryScript(
            @PathVariable UUID lockedIdeaId,
            @PathVariable UUID storyIdeaId,
            @Valid @RequestBody(required = false) GenerateStoryScriptRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(ideaService.generateStoryScriptForStoryIdea(lockedIdeaId, storyIdeaId, request, tenantId, userId));
    }

    @PutMapping("/{lockedIdeaId}/story-ideas/{storyIdeaId}/script")
    public ResponseEntity<GeneratedStoryScriptResponse> saveStoryScript(
            @PathVariable UUID lockedIdeaId,
            @PathVariable UUID storyIdeaId,
            @Valid @RequestBody SaveStoryScriptRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(ideaService.saveStoryScript(lockedIdeaId, storyIdeaId, request, tenantId, userId));
    }

    @GetMapping("/{lockedIdeaId}/story-ideas/{storyIdeaId}/cast-mappings")
    public ResponseEntity<CharacterCastMappingResponse> listCharacterCastMappings(
            @PathVariable UUID lockedIdeaId,
            @PathVariable UUID storyIdeaId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(characterCastMappingService.listMappings(lockedIdeaId, storyIdeaId, tenantId, userId));
    }

    @PutMapping("/{lockedIdeaId}/story-ideas/{storyIdeaId}/cast-mappings")
    public ResponseEntity<CharacterCastMappingResponse> saveCharacterCastMappings(
            @PathVariable UUID lockedIdeaId,
            @PathVariable UUID storyIdeaId,
            @Valid @RequestBody CharacterCastMappingRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(characterCastMappingService.saveMappings(lockedIdeaId, storyIdeaId, request, tenantId, userId));
    }

    @PostMapping("/{lockedIdeaId}/story-ideas/{storyIdeaId}/screenplay/generate")
    public ResponseEntity<GeneratedScriptResponse> generateStoryIdeaScript(
            @PathVariable UUID lockedIdeaId,
            @PathVariable UUID storyIdeaId,
            @Valid @RequestBody(required = false) GenerateStoryIdeaScriptRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(ideaService.generateScriptForStoryIdea(lockedIdeaId, storyIdeaId, request, tenantId, userId));
    }

    @PutMapping("/{lockedIdeaId}/story-ideas/{storyIdeaId}/screenplay/{scriptId}")
    public ResponseEntity<GeneratedScriptResponse> saveGeneratedScript(
            @PathVariable UUID lockedIdeaId,
            @PathVariable UUID storyIdeaId,
            @PathVariable UUID scriptId,
            @Valid @RequestBody SaveGeneratedScriptRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(ideaService.saveEditedScript(lockedIdeaId, storyIdeaId, scriptId, request, tenantId, userId));
    }
}
