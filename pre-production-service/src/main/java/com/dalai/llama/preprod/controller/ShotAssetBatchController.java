package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.ShotAssetBatchDeadLetterView;
import com.dalai.llama.preprod.dto.ShotAssetBatchJobView;
import com.dalai.llama.preprod.dto.ShotAssetCompletionView;
import com.dalai.llama.preprod.service.ShotAssetBatchDeadLetterService;
import com.dalai.llama.preprod.service.ShotAssetBatchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ShotAssetBatchController extends BaseController {

    private final ShotAssetBatchService shotAssetBatchService;
    private final ShotAssetBatchDeadLetterService deadLetterService;

    public ShotAssetBatchController(ShotAssetBatchService shotAssetBatchService, ShotAssetBatchDeadLetterService deadLetterService) {
        this.shotAssetBatchService = shotAssetBatchService;
        this.deadLetterService = deadLetterService;
    }

    /** Queues the run and returns immediately -- ShotAssetBatchWorker does the actual generation,
     * one step per tick, on its own schedule. Poll {@link #status} for progress. */
    @PostMapping("/v1/projects/{projectId}/shots/generate-all-assets")
    public ResponseEntity<ShotAssetBatchJobView> start(@PathVariable UUID projectId) {
        return ResponseEntity.ok(shotAssetBatchService.start(tenant().tenantId(), projectId));
    }

    @GetMapping("/v1/projects/{projectId}/shots/generate-all-assets/status")
    public ResponseEntity<ShotAssetBatchJobView> status(@PathVariable UUID projectId) {
        return ResponseEntity.ok(shotAssetBatchService.status(tenant().tenantId(), projectId));
    }

    /** Per-shot "does this shot have every asset it needs" -- drives the shots list's green check,
     * checked against real ground truth (does the row exist), not batch/dead-letter bookkeeping. */
    @GetMapping("/v1/projects/{projectId}/shots/generate-all-assets/completion")
    public ResponseEntity<List<ShotAssetCompletionView>> completion(@PathVariable UUID projectId) {
        return ResponseEntity.ok(shotAssetBatchService.completion(tenant().tenantId(), projectId));
    }

    /** Steps that exhausted their automatic retries during a run and need a deliberate, individual
     * fix + retry -- see ShotAssetBatchWorker's javadoc on why these are never auto-retried. */
    @GetMapping("/v1/projects/{projectId}/shots/generate-all-assets/dead-letters")
    public ResponseEntity<List<ShotAssetBatchDeadLetterView>> deadLetters(@PathVariable UUID projectId) {
        return ResponseEntity.ok(deadLetterService.list(tenant().tenantId(), projectId));
    }

    @PostMapping("/v1/projects/{projectId}/shots/generate-all-assets/dead-letters/{deadLetterId}/retry")
    public ResponseEntity<Void> retryDeadLetter(@PathVariable UUID projectId, @PathVariable UUID deadLetterId) {
        deadLetterService.retry(tenant().tenantId(), projectId, deadLetterId);
        return ResponseEntity.noContent().build();
    }
}
