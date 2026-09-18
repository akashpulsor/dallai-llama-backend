package com.dalai.llama.postprod.controller;

import com.dalai.llama.postprod.domain.entity.FilmRender;
import com.dalai.llama.postprod.dto.FilmReadinessView;
import com.dalai.llama.postprod.dto.FilmRenderView;
import com.dalai.llama.postprod.service.clip.FilmAssemblyService;
import com.dalai.llama.postprod.web.TenantContext;
import com.dalai.llama.postprod.web.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Putting the film together and showing it to the client.
 *
 * <p>Joining is queued, not performed here -- ffmpeg over a whole project runs for minutes, so the
 * POST records the request and the page polls {@code /latest}. {@code /readiness} exists so the
 * button can say WHY it is disabled, naming the shots still to generate, rather than being greyed
 * out with no explanation.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/post-production/projects/{projectId}/film")
public class FilmRenderController {

    private final FilmAssemblyService filmAssemblyService;

    /** Whether every shot has a cut yet, and which do not. */
    @GetMapping("/readiness")
    public ResponseEntity<FilmReadinessView> readiness(@PathVariable UUID projectId) {
        TenantContext ctx = TenantContextHolder.get();
        FilmAssemblyService.Readiness readiness = filmAssemblyService.readiness(ctx.tenantId(), projectId);
        return ResponseEntity.ok(new FilmReadinessView(
                readiness.isReady(), readiness.total(), readiness.ready(), readiness.missingShotRefs()));
    }

    /** Queue a join of every shot's current cut. Returns as soon as it is queued. */
    @PostMapping
    public ResponseEntity<FilmRenderView> assemble(@PathVariable UUID projectId) {
        TenantContext ctx = TenantContextHolder.get();
        FilmRender render = filmAssemblyService.request(ctx.tenantId(), projectId, ctx.userId());
        return ResponseEntity.ok(toView(render));
    }

    /**
     * The newest film for this project, whatever state it is in. What the page polls.
     *
     * <p>Carries the queue position with it, because films are joined one at a time across every
     * tenant: a creator whose film is third in line is looking at the same spinner as one whose film
     * is being worked on right now, and only this endpoint knows the difference.
     */
    @GetMapping("/latest")
    public ResponseEntity<FilmRenderView> latest(@PathVariable UUID projectId) {
        return filmAssemblyService.latest(projectId)
                .map(render -> {
                    FilmAssemblyService.QueueWait wait = filmAssemblyService.queueWait(render);
                    return ResponseEntity.ok(FilmRenderView.of(render,
                            filmAssemblyService.playableUrl(render),
                            wait.filmsAhead(), wait.estimatedWaitSeconds()));
                })
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** The creator's own edit of the film. Recorded as a new render, so the joined version and the
     * hand-edited one both survive and publishing can move between them. */
    @PostMapping("/uploaded")
    public ResponseEntity<FilmRenderView> uploaded(@PathVariable UUID projectId,
                                                   @org.springframework.web.bind.annotation.RequestParam("file")
                                                   org.springframework.web.multipart.MultipartFile file) {
        TenantContext ctx = TenantContextHolder.get();
        return ResponseEntity.ok(toView(
                filmAssemblyService.uploadEdited(ctx.tenantId(), projectId, ctx.userId(), file)));
    }

    /** Show this film on the client's review page, or take it back down. */
    @PostMapping("/{renderId}/publish")
    public ResponseEntity<FilmRenderView> publish(@PathVariable UUID projectId,
                                                  @PathVariable UUID renderId,
                                                  @RequestParam(defaultValue = "true") boolean published) {
        TenantContext ctx = TenantContextHolder.get();
        return ResponseEntity.ok(toView(filmAssemblyService.setPublished(ctx.tenantId(), renderId, published)));
    }

    private FilmRenderView toView(FilmRender render) {
        return FilmRenderView.of(render, filmAssemblyService.playableUrl(render));
    }
}
