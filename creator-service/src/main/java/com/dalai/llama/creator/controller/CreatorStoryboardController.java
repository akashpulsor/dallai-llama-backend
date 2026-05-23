package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.request.GenerateProductionPlanRequest;
import com.dalai.llama.creator.dto.request.GenerateStoryboardRequest;
import com.dalai.llama.creator.dto.response.GenerationJobResponse;
import com.dalai.llama.creator.dto.response.ShotProductionPlanTagResponse;
import com.dalai.llama.creator.dto.response.StoryboardResponse;
import com.dalai.llama.creator.service.CreatorProductionPlanAsyncService;
import com.dalai.llama.creator.service.CreatorStoryboardAsyncService;
import com.dalai.llama.creator.service.GenerationJobService;
import com.dalai.llama.creator.service.ProductionPlanTagService;
import com.dalai.llama.creator.service.StoryboardService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/creator/storyboards")
public class CreatorStoryboardController {

    private final StoryboardService storyboardService;
    private final ProductionPlanTagService productionPlanTagService;
    private final CreatorProductionPlanAsyncService asyncProductionPlanService;
    private final CreatorStoryboardAsyncService asyncStoryboardService;
    private final GenerationJobService generationJobService;

    public CreatorStoryboardController(
            StoryboardService storyboardService,
            ProductionPlanTagService productionPlanTagService,
            CreatorProductionPlanAsyncService asyncProductionPlanService,
            CreatorStoryboardAsyncService asyncStoryboardService,
            GenerationJobService generationJobService
    ) {
        this.storyboardService = storyboardService;
        this.productionPlanTagService = productionPlanTagService;
        this.asyncProductionPlanService = asyncProductionPlanService;
        this.asyncStoryboardService = asyncStoryboardService;
        this.generationJobService = generationJobService;
    }

    @GetMapping("/scripts/{scriptId}/plans")
    public ResponseEntity<List<ShotProductionPlanTagResponse>> listShotPlans(
            @PathVariable UUID scriptId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity.ok(productionPlanTagService.listTagsForScript(scriptId, tenantId, userId));
    }

    @PostMapping("/scripts/{scriptId}/plans/generate-async")
    public ResponseEntity<GenerationJobResponse> generateShotPlansAsync(
            @PathVariable UUID scriptId,
            @Valid @RequestBody(required = false) GenerateProductionPlanRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncProductionPlanService.startProductionPlanGeneration(
                        scriptId,
                        request == null ? null : request.styleKey(),
                        request == null ? null : request.focusedShotNumber(),
                        tenantId,
                        userId
                )));
    }

    @PostMapping("/scripts/{scriptId}/generate")
    public ResponseEntity<StoryboardResponse> generateFromFinalScript(
            @PathVariable UUID scriptId,
            @Valid @RequestBody(required = false) GenerateStoryboardRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(storyboardService.generateFromFinalScript(scriptId, request, tenantId, userId));
    }

    @PostMapping("/scripts/{scriptId}/generate-async")
    public ResponseEntity<GenerationJobResponse> generateFromFinalScriptAsync(
            @PathVariable UUID scriptId,
            @Valid @RequestBody(required = false) GenerateStoryboardRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(generationJobService.toResponse(asyncStoryboardService.startStoryboardGeneration(scriptId, request, tenantId, userId)));
    }

    @PostMapping("/scripts/{scriptId}/shots/{shotNumber}/images/{imageKind}")
    public ResponseEntity<com.dalai.llama.creator.dto.response.StoryboardSceneResponse> generateShotImage(
            @PathVariable UUID scriptId,
            @PathVariable Integer shotNumber,
            @PathVariable String imageKind,
            @Valid @RequestBody(required = false) GenerateStoryboardRequest request,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(storyboardService.generateShotImage(scriptId, shotNumber == null ? 1 : shotNumber, imageKind, request, tenantId, userId));
    }
}
