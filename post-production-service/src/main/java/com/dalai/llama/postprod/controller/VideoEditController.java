package com.dalai.llama.postprod.controller;

import com.dalai.llama.postprod.dto.EditVideoRequest;
import com.dalai.llama.postprod.dto.VideoEditView;
import com.dalai.llama.postprod.service.VideoEditResult;
import com.dalai.llama.postprod.service.VideoEditService;
import com.dalai.llama.postprod.web.TenantContextHolder;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** "Select a portion of a clip, or give the whole video, ask for a change, optionally with a
 * reference image" -- see VideoEditService's class comment for why this is standalone/testable
 * rather than wired into a durable job yet. */
@RestController
public class VideoEditController {

    private final VideoEditService videoEditService;

    public VideoEditController(VideoEditService videoEditService) {
        this.videoEditService = videoEditService;
    }

    @PostMapping("/v1/post-production/video-edit")
    public ResponseEntity<VideoEditView> editVideo(@Valid @RequestBody EditVideoRequest request) {
        UUID tenantId = TenantContextHolder.get().tenantId();
        VideoEditResult result = videoEditService.editVideo(
                tenantId, "post-prod-video-edit-" + UUID.randomUUID(),
                request.sourceVideoUrl(), request.startSeconds(), request.endSeconds(),
                request.editInstruction(), request.referenceImageUrl(), request.model());
        return ResponseEntity.ok(new VideoEditView(request.model(), result.editedVideoUrl()));
    }
}
