package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.domain.ReviewActor;
import com.dalai.llama.preprod.dto.CreativeDirectionBoardView;
import com.dalai.llama.preprod.dto.CreativeDirectionFeedbackRequest;
import com.dalai.llama.preprod.dto.CreativeDirectionView;
import com.dalai.llama.preprod.dto.ReviseCreativeDirectionRequest;
import com.dalai.llama.preprod.service.creativedirection.CreativeDirectionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The creator's Creative Direction stage for a project -- see {@link CreativeDirectionService}.
 * Tenant-scoped like every other /v1/projects endpoint; the client reaches the same treatments
 * through the review link ({@link PublicProjectController}). */
@RestController
public class CreativeDirectionController extends BaseController {

    private final CreativeDirectionService creativeDirectionService;

    public CreativeDirectionController(CreativeDirectionService creativeDirectionService) {
        this.creativeDirectionService = creativeDirectionService;
    }

    /** Starts a round of {@code count} new alternatives (5-10, default 6) and returns at once with
     * the round PENDING -- poll the board until it completes. An approved direction is kept until
     * another is approved. */
    @PostMapping("/v1/projects/{projectId}/creative-directions/generate")
    public ResponseEntity<CreativeDirectionBoardView> generate(@PathVariable UUID projectId,
                                                               @RequestParam(required = false) Integer count) {
        return ResponseEntity.accepted().body(creativeDirectionService.generate(tenant().tenantId(), projectId, tenant().userId(), count));
    }

    /** One page of the latest completed round, the AI's recommendation first on page 0. */
    @GetMapping("/v1/projects/{projectId}/creative-directions")
    public ResponseEntity<CreativeDirectionBoardView> board(@PathVariable UUID projectId,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "" + CreativeDirectionService.DEFAULT_PAGE_SIZE) int size) {
        return ResponseEntity.ok(creativeDirectionService.board(tenant().tenantId(), projectId, page, size));
    }

    /** 204 when no direction is approved yet. */
    @GetMapping("/v1/projects/{projectId}/creative-directions/approved")
    public ResponseEntity<CreativeDirectionView> approved(@PathVariable UUID projectId) {
        return creativeDirectionService.approved(tenant().tenantId(), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/v1/projects/{projectId}/creative-directions/{directionId}")
    public ResponseEntity<CreativeDirectionView> get(@PathVariable UUID projectId, @PathVariable UUID directionId) {
        return ResponseEntity.ok(creativeDirectionService.get(tenant().tenantId(), projectId, directionId));
    }

    @PostMapping("/v1/projects/{projectId}/creative-directions/{directionId}/select")
    public ResponseEntity<CreativeDirectionView> select(@PathVariable UUID projectId, @PathVariable UUID directionId) {
        return ResponseEntity.ok(creativeDirectionService.select(tenant().tenantId(), projectId, directionId));
    }

    @PostMapping("/v1/projects/{projectId}/creative-directions/{directionId}/feedback")
    public ResponseEntity<CreativeDirectionView> feedback(@PathVariable UUID projectId, @PathVariable UUID directionId,
                                                          @Valid @RequestBody CreativeDirectionFeedbackRequest request) {
        return ResponseEntity.ok(creativeDirectionService.addFeedback(tenant().tenantId(), projectId, directionId, request,
                ReviewActor.CREATOR, tenant().userId()));
    }

    /** Rewrites the treatment against its recorded feedback as a new version. */
    @PostMapping("/v1/projects/{projectId}/creative-directions/{directionId}/revise")
    public ResponseEntity<CreativeDirectionView> revise(@PathVariable UUID projectId, @PathVariable UUID directionId,
                                                        @Valid @RequestBody(required = false) ReviseCreativeDirectionRequest request) {
        return ResponseEntity.ok(creativeDirectionService.revise(tenant().tenantId(), projectId, directionId,
                request == null ? null : request.note()));
    }

    @PostMapping("/v1/projects/{projectId}/creative-directions/{directionId}/approve")
    public ResponseEntity<CreativeDirectionView> approve(@PathVariable UUID projectId, @PathVariable UUID directionId) {
        return ResponseEntity.ok(creativeDirectionService.approve(tenant().tenantId(), projectId, directionId,
                ReviewActor.CREATOR, tenant().userId()));
    }
}
