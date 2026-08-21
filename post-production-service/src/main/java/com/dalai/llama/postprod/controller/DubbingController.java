package com.dalai.llama.postprod.controller;

import com.dalai.llama.postprod.dto.DubbingJobView;
import com.dalai.llama.postprod.service.DubbingOrchestrator;
import com.dalai.llama.postprod.web.TenantContext;
import com.dalai.llama.postprod.web.TenantContextHolder;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.UUID;

/** The standalone "dub any uploaded video" flow -- see DubbingOrchestrator's class comment for
 * the full pipeline and its named v1 simplifications. */
@RestController
public class DubbingController {

    private final DubbingOrchestrator orchestrator;

    public DubbingController(DubbingOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping(value = "/v1/dubbing/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DubbingJobView> createJob(
            @RequestParam("file") MultipartFile file,
            @RequestParam("targetLanguage") String targetLanguage
    ) {
        TenantContext ctx = tenant();
        return ResponseEntity.ok(orchestrator.dub(ctx.tenantId(), ctx.userId(), file, targetLanguage));
    }

    @GetMapping("/v1/dubbing/jobs/{jobId}")
    public ResponseEntity<DubbingJobView> getJob(@PathVariable UUID jobId) {
        return ResponseEntity.ok(orchestrator.getJob(tenant().tenantId(), jobId));
    }

    @GetMapping("/v1/dubbing/jobs/{jobId}/video")
    public ResponseEntity<Void> getVideo(@PathVariable UUID jobId) {
        String signedUrl = orchestrator.getVideoUrl(tenant().tenantId(), jobId);
        return ResponseEntity.status(302).location(URI.create(signedUrl)).build();
    }

    private TenantContext tenant() {
        return TenantContextHolder.get();
    }
}
