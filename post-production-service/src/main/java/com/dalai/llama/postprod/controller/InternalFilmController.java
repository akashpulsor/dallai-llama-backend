package com.dalai.llama.postprod.controller;

import com.dalai.llama.postprod.domain.entity.FilmRender;
import com.dalai.llama.postprod.service.clip.FilmAssemblyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The film a creator has chosen to show, for pre-production's client review page.
 *
 * <p>The review page is pre-production's, and it has always asked another service where the finished
 * video is rather than holding one itself. It used to ask video-generation-service; now that
 * assembling belongs here, it asks here.
 *
 * <p>Returns ONLY a published film. The gate is enforced on this side rather than trusted to the
 * caller: a URL that leaves this method is one the creator has agreed a client may watch, so there
 * is no way for a mistake on the review page to expose a cut nobody chose to show.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/tenants/{tenantId}/projects/{projectId}")
public class InternalFilmController {

    private final FilmAssemblyService filmAssemblyService;
    private final com.dalai.llama.postprod.service.clip.ShotClipVersionService clipVersionService;

    /** The shots a creator has chosen to show on their own, ahead of any film. Same gate as the
     * film: only published rows leave this method. */
    @GetMapping("/clips/published")
    public ResponseEntity<java.util.List<com.dalai.llama.postprod.dto.ShotClipVersionView>> publishedShots(
            @PathVariable UUID tenantId, @PathVariable UUID projectId) {
        return ResponseEntity.ok(clipVersionService.publishedForProject(tenantId, projectId).stream()
                .map(v -> com.dalai.llama.postprod.dto.ShotClipVersionView.of(v, clipVersionService.playableUrl(v)))
                .toList());
    }

    @GetMapping("/film/published")
    public ResponseEntity<PublishedFilmView> published(@PathVariable UUID tenantId,
                                                        @PathVariable UUID projectId) {
        return filmAssemblyService.latestPublished(projectId)
                .filter(render -> tenantId.equals(render.getTenantId()))
                .map(this::toView)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(PublishedFilmView.none()));
    }

    private PublishedFilmView toView(FilmRender render) {
        return new PublishedFilmView(
                true,
                render.getRenderId(),
                filmAssemblyService.playableUrl(render),
                render.getDurationSeconds(),
                render.getWidth(),
                render.getHeight(),
                render.getPublishedAt(),
                render.getCompletedAt());
    }

    /** @param available false when this project has no published film -- a normal state, not an
     *                   error, and the review page renders its "coming shortly" panel for it. */
    public record PublishedFilmView(boolean available,
                                     UUID renderId,
                                     String videoUrl,
                                     BigDecimal durationSeconds,
                                     Integer width,
                                     Integer height,
                                     OffsetDateTime publishedAt,
                                     OffsetDateTime completedAt) {

        static PublishedFilmView none() {
            return new PublishedFilmView(false, null, null, null, null, null, null, null);
        }
    }
}
