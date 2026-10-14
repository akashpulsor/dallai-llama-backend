package com.dalai.llama.postprod.controller;

import com.dalai.llama.postprod.dto.ShotConformDtos;
import com.dalai.llama.postprod.service.clip.ShotConformService;
import com.dalai.llama.postprod.web.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Conform a shot's newest clip to a length: slowed (with frame interpolation unless
 * {@code interpolate} is false) or trimmed, with its line and music laid on. Queued -- 202 with a
 * request id to poll; the result becomes the shot's active cut.
 */
@RestController
@RequiredArgsConstructor
public class ShotConformController {

    private final ShotConformService conformService;

    /** The creator's slow-down: a new version to preview and choose, not an immediate swap. */
    @PostMapping("/v1/post-production/projects/{projectId}/shots/{shotId}/conform")
    public ResponseEntity<ShotConformDtos.View> request(@PathVariable UUID projectId, @PathVariable UUID shotId,
                                                        @Valid @RequestBody ShotConformDtos.Request request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(conformService.request(TenantContextHolder.get().tenantId(), projectId, shotId, request, false));
    }

    @GetMapping("/v1/post-production/projects/{projectId}/shots/{shotId}/conform/{requestId}")
    public ResponseEntity<ShotConformDtos.View> status(@PathVariable UUID projectId, @PathVariable UUID shotId,
                                                       @PathVariable UUID requestId) {
        return ResponseEntity.ok(conformService.status(TenantContextHolder.get().tenantId(), requestId));
    }

    /** For video-generation-service's finishing after a render: the result becomes the shot's clip. */
    @PostMapping("/api/v1/internal/tenants/{tenantId}/projects/{projectId}/shots/{shotId}/conform")
    public ResponseEntity<ShotConformDtos.View> requestInternal(@PathVariable UUID tenantId, @PathVariable UUID projectId,
                                                                @PathVariable UUID shotId,
                                                                @Valid @RequestBody ShotConformDtos.Request request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(conformService.request(tenantId, projectId, shotId, request, true));
    }
}
