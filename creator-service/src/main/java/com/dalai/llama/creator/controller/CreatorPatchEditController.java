package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.response.GenerationJobResponse;
import com.dalai.llama.creator.service.CreatorPatchEditService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Backs creator-ui's AI Patch Editor "Generate AI edit" button
 * (useGeneratePatchEditAsyncMutation in creatorEndpoints.js). The UI only ever shows
 * a "Provider" dropdown - model selection is a backend concern, see
 * {@link CreatorPatchEditService}. `provider`/`model` are accepted here for forward
 * compatibility but the only implemented route today is Kling via fal.ai.
 */
@RestController
@RequestMapping("/api/v1/creator/patch-editor/edits")
public class CreatorPatchEditController {

    private final CreatorPatchEditService patchEditService;

    public CreatorPatchEditController(CreatorPatchEditService patchEditService) {
        this.patchEditService = patchEditService;
    }

    @PostMapping(value = "/generate-async", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GenerationJobResponse> generateAsync(
            @RequestParam("file") MultipartFile file,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "negativePrompt", required = false) String negativePrompt,
            @RequestParam(value = "referenceImages", required = false) List<MultipartFile> referenceImages,
            @RequestParam(value = "referenceVideos", required = false) List<MultipartFile> referenceVideos,
            @RequestParam(value = "startSeconds", required = false) Double startSeconds,
            @RequestParam(value = "endSeconds", required = false) Double endSeconds,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            Authentication authentication
    ) {
        String userId = authentication == null ? "anonymous" : authentication.getName();
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(patchEditService.startGenerate(file, prompt, negativePrompt, referenceImages, referenceVideos, startSeconds, endSeconds, tenantId, userId));
    }
}
