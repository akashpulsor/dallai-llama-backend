package com.dalai.llama.postprod.controller;

import com.dalai.llama.postprod.domain.entity.ShotClipVersion;
import com.dalai.llama.postprod.dto.ShotClipVersionView;
import com.dalai.llama.postprod.service.clip.ShotClipVersionService;
import com.dalai.llama.postprod.web.TenantContext;
import com.dalai.llama.postprod.web.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Every shot in a project and the cut it is currently on, in one call.
 *
 * <p>The editor needs all of them, not only the joined film -- a creator opens it to work on one
 * shot, save the edit back, and re-join. Asking per shot would be one request per card, which is
 * the shape that made the video page slow to open in the first place; this is one indexed query,
 * cached by project and evicted whenever a cut is made or accepted.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/post-production/projects/{projectId}/clips")
public class ProjectClipsController {

    private final ShotClipVersionService clipVersionService;

    /** The current cut of every shot. What the editor lists. */
    @GetMapping
    public ResponseEntity<List<ShotClipVersionView>> current(@PathVariable UUID projectId) {
        TenantContext ctx = TenantContextHolder.get();
        return ResponseEntity.ok(toViews(clipVersionService.activeForProject(ctx.tenantId(), projectId)));
    }

    /** The shots the creator has chosen to show a client on their own, ahead of any film. */
    @GetMapping("/published")
    public ResponseEntity<List<ShotClipVersionView>> published(@PathVariable UUID projectId) {
        TenantContext ctx = TenantContextHolder.get();
        return ResponseEntity.ok(toViews(clipVersionService.publishedForProject(ctx.tenantId(), projectId)));
    }

    private List<ShotClipVersionView> toViews(List<ShotClipVersion> versions) {
        return versions.stream()
                .map(version -> ShotClipVersionView.of(version, clipVersionService.playableUrl(version)))
                .toList();
    }
}
