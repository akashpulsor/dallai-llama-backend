package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.service.showcase.ShowcaseSourceService;
import com.dalai.llama.preprod.service.showcase.ShowcaseSourceService.PosterFrameView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The film's thumbnail on the public profile, chosen from its shots' generated frames. */
@RestController
public class ShowcasePosterController extends BaseController {

    private final ShowcaseSourceService showcaseSourceService;

    public ShowcasePosterController(ShowcaseSourceService showcaseSourceService) {
        this.showcaseSourceService = showcaseSourceService;
    }

    @GetMapping("/v1/projects/{projectId}/poster-frames")
    public ResponseEntity<List<PosterFrameView>> frames(@PathVariable UUID projectId) {
        return ResponseEntity.ok(showcaseSourceService.posterFrameViews(tenant().tenantId(), projectId));
    }

    /** {@code imageId} null goes back to the default, the first shot's frame. */
    @PutMapping("/v1/projects/{projectId}/poster-frame")
    public ResponseEntity<List<PosterFrameView>> choose(@PathVariable UUID projectId, @RequestBody ChoosePoster request) {
        return ResponseEntity.ok(showcaseSourceService.choosePoster(tenant().tenantId(), projectId, request.imageId()));
    }

    public record ChoosePoster(UUID imageId) {
    }
}
