package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.CreateShotRequest;
import com.dalai.llama.preprod.dto.DispatchShotRequest;
import com.dalai.llama.preprod.dto.GenerationThoughtView;
import com.dalai.llama.preprod.dto.ShotDispatchResponse;
import com.dalai.llama.preprod.dto.ShotListJobView;
import com.dalai.llama.preprod.dto.ShotView;
import com.dalai.llama.preprod.dto.UpdateShotRequest;
import com.dalai.llama.preprod.service.GenerationThoughtService;
import com.dalai.llama.preprod.service.ShotContextAssemblyService;
import com.dalai.llama.preprod.service.ShotListGenerationJobService;
import com.dalai.llama.preprod.service.ShotListGenerationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ShotListController extends BaseController {

    private final ShotListGenerationService shotListGenerationService;
    private final ShotListGenerationJobService shotListGenerationJobService;
    private final ShotContextAssemblyService shotContextAssemblyService;
    private final GenerationThoughtService generationThoughtService;
    private final com.dalai.llama.preprod.service.ShotReorderService shotReorderService;

    public ShotListController(
            ShotListGenerationService shotListGenerationService,
            ShotListGenerationJobService shotListGenerationJobService,
            ShotContextAssemblyService shotContextAssemblyService,
            GenerationThoughtService generationThoughtService,
            com.dalai.llama.preprod.service.ShotReorderService shotReorderService
    ) {
        this.shotListGenerationService = shotListGenerationService;
        this.shotListGenerationJobService = shotListGenerationJobService;
        this.shotContextAssemblyService = shotContextAssemblyService;
        this.generationThoughtService = generationThoughtService;
        this.shotReorderService = shotReorderService;
    }

    /**
     * Async job submission: returns immediately with the job id, generation happens on
     * llm-gateway's Kafka worker. The UI must poll {@link #getGenerateListJob} until status
     * reaches SUCCEEDED (then re-fetch /v1/projects/{projectId}/shots) or FAILED.
     */
    @PostMapping("/v1/projects/{projectId}/shots/generate-list")
    public ResponseEntity<ShotListJobView> generateList(@PathVariable UUID projectId) {
        UUID tenantId = tenant().tenantId();
        ShotListJobView job = shotListGenerationJobService.submit(tenantId, projectId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }

    /** Status endpoint the UI polls after {@link #generateList} responds. */
    @GetMapping("/v1/projects/{projectId}/shots/generate-list/{jobId}")
    public ResponseEntity<ShotListJobView> getGenerateListJob(
            @PathVariable UUID projectId, @PathVariable UUID jobId
    ) {
        return ResponseEntity.ok(shotListGenerationJobService.get(tenant().tenantId(), projectId, jobId));
    }

    @GetMapping("/v1/projects/{projectId}/shots")
    public ResponseEntity<List<ShotView>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(shotListGenerationService.list(tenant().tenantId(), projectId));
    }

    /** Manually inserts one shot -- the "design a shot by hand" counterpart to {@link
     * #generateList}. See {@link CreateShotRequest}'s class comment for scope and the
     * regenerate-wipes-manual-shots caveat. */
    @PostMapping("/v1/projects/{projectId}/shots")
    public ResponseEntity<ShotView> createShot(@PathVariable UUID projectId, @Valid @RequestBody CreateShotRequest request) {
        return ResponseEntity.ok(shotListGenerationService.createShot(tenant().tenantId(), projectId, request));
    }

    /** Hand-edit a shot's script line and/or length after the fact -- see {@link
     * UpdateShotRequest}'s class comment. */
    @PatchMapping("/v1/shots/{shotId}")
    public ResponseEntity<ShotView> updateShot(@PathVariable UUID shotId, @Valid @RequestBody UpdateShotRequest request) {
        return ResponseEntity.ok(shotListGenerationService.updateShot(tenant().tenantId(), shotId, request));
    }

    /**
     * What moving this shot would do -- asked before anything moves.
     *
     * <p>A read, with no side effects: the shot stays exactly where it is. Answers separately for
     * the story, the shots either side, and anything already generated, because those cost
     * different amounts to put right.
     */
    @GetMapping("/v1/projects/{projectId}/shots/{shotId}/reorder-impact")
    public ResponseEntity<com.dalai.llama.preprod.service.ShotReorderService.ReorderImpact> reorderImpact(
            @PathVariable UUID projectId, @PathVariable UUID shotId,
            @org.springframework.web.bind.annotation.RequestParam int position) {
        return ResponseEntity.ok(
                shotReorderService.assessImpact(tenant().tenantId(), projectId, shotId, position));
    }

    /**
     * Moves the shot and renumbers the rest. Changes nothing else.
     *
     * <p>Not the script, not the descriptions, not a single generated clip -- a creator reordering
     * their edit is not asking for their plan to be rewritten. Generated video follows on its own,
     * because clips are keyed to a shot and everything downstream orders by shot_number.
     */
    @PostMapping("/v1/projects/{projectId}/shots/{shotId}/reorder")
    public ResponseEntity<List<ShotView>> reorder(
            @PathVariable UUID projectId, @PathVariable UUID shotId,
            @Valid @RequestBody ReorderShotRequest request) {
        shotReorderService.reorder(tenant().tenantId(), projectId, shotId, request.position());
        return ResponseEntity.ok(shotListGenerationService.list(tenant().tenantId(), projectId));
    }

    /** @param position where the shot should end up, 1-based. */
    public record ReorderShotRequest(@jakarta.validation.constraints.NotNull Integer position) {}

    @PostMapping("/v1/shots/{shotId}/generate")
    public ResponseEntity<ShotDispatchResponse> dispatch(@PathVariable UUID shotId, @RequestBody(required = false) DispatchShotRequest request) {
        boolean autoApprove = request != null && request.autoApprove();
        Boolean dialogue = request == null ? null : request.dialogue();
        Boolean captions = request == null ? null : request.captions();
        return ResponseEntity.ok(shotContextAssemblyService.dispatch(tenant().tenantId(), shotId, autoApprove, dialogue, captions));
    }

    @GetMapping("/v1/shots/{shotId}/thoughts")
    public ResponseEntity<List<GenerationThoughtView>> thoughts(@PathVariable UUID shotId) {
        return ResponseEntity.ok(generationThoughtService.list(shotId));
    }
}
