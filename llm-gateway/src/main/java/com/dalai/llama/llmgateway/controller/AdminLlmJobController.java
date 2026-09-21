package com.dalai.llama.llmgateway.controller;

import com.dalai.llama.llmgateway.dto.RetryLlmJobResponse;
import com.dalai.llama.llmgateway.dto.StuckLlmJobView;
import com.dalai.llama.llmgateway.service.AdminLlmJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Ops-dashboard endpoints for LLM job diagnostics + retry.
 *
 * <p>Path prefix: {@code /api/v1/internal/admin/**}. The {@code /internal/} segment matches the
 * existing {@link com.dalai.llama.llmgateway.config.SecurityConfig#internalFilterChain} pattern,
 * which is permitAll at the Spring layer -- the auth perimeter is the Istio gateway (oauth2-proxy
 * + Keycloak {@code dalai_admin} role, wired up in the infra chart). Two consequences:
 *
 * <ol>
 *   <li>These endpoints are not routable through the public {@code api.dalaillama.in}
 *       VirtualService (only {@code ops.dalaillama.in} routes {@code /admin/**}).</li>
 *   <li>Adding a new admin endpoint means adding it here; the security config never has to
 *       change again for admin surface growth.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/internal/admin/llm-jobs")
@RequiredArgsConstructor
public class AdminLlmJobController {

    private final AdminLlmJobService adminLlmJobService;

    /** @param lookbackHours how far back to scan; defaults to 24h. */
    @GetMapping("/stuck")
    public ResponseEntity<List<StuckLlmJobView>> stuck(
            @RequestParam(name = "lookbackHours", defaultValue = "24") long lookbackHours
    ) {
        return ResponseEntity.ok(adminLlmJobService.listStuck(Duration.ofHours(lookbackHours)));
    }

    /** Every job across every status in the window -- the "what has this platform run recently"
     * view. Same DTO as stuck; the UI just doesn't hide COMPLETED rows. */
    @GetMapping("/recent")
    public ResponseEntity<List<StuckLlmJobView>> recent(
            @RequestParam(name = "lookbackHours", defaultValue = "24") long lookbackHours
    ) {
        return ResponseEntity.ok(adminLlmJobService.listAll(Duration.ofHours(lookbackHours)));
    }

    @PostMapping("/{jobId}/retry")
    public ResponseEntity<RetryLlmJobResponse> retry(@PathVariable UUID jobId) {
        return ResponseEntity.ok(adminLlmJobService.retry(jobId));
    }
}
