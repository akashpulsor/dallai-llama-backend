package com.dalai.llama.videogen.controller;

import com.dalai.llama.videogen.dto.GenerateShotRequest;
import com.dalai.llama.videogen.dto.GenerateShotResponse;
import com.dalai.llama.videogen.service.ShotGenerationOrchestrator;
import com.dalai.llama.videogen.web.TenantContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Sole real caller is pre-production-service's {@code VideoGenClient} -- {@code
 * /api/v1/internal/**} is permitAll (see {@code SecurityConfig}), so tenantId comes from the
 * path, not a JWT-derived context. {@code userId} is null on this path, same as any other
 * request that arrives without a JWT -- {@code ShotGenerationOrchestrator} already tolerates
 * that (see {@code TenantContext}). */
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}")
public class InternalVideoGenController {

    private final ShotGenerationOrchestrator orchestrator;

    public InternalVideoGenController(ShotGenerationOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/shots/generate")
    public GenerateShotResponse generate(@PathVariable UUID tenantId, @Valid @RequestBody GenerateShotRequest request) {
        return orchestrator.generate(new TenantContext(tenantId, null), request);
    }
}
