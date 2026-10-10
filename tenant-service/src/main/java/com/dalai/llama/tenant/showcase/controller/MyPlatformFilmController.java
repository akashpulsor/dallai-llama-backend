package com.dalai.llama.tenant.showcase.controller;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.TenantService;
import com.dalai.llama.tenant.showcase.dto.MyShowcaseItemView;
import com.dalai.llama.tenant.showcase.dto.PlatformFilmDtos.LinkPlatformFilmRequest;
import com.dalai.llama.tenant.showcase.dto.PlatformFilmDtos.OfficialUploadRequest;
import com.dalai.llama.tenant.showcase.dto.PlatformFilmDtos.OfficialUploadView;
import com.dalai.llama.tenant.showcase.dto.PlatformFilmDtos.YouTubeKitView;
import com.dalai.llama.tenant.showcase.service.PlatformPublishService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/** Publishing a project's finished film to the showcase, from the film bar. The project must be
 * the caller's (pre-production answers 404 otherwise, surfaced here as 400). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants/me/showcase/projects/{projectId}")
public class MyPlatformFilmController {

    private final TenantService tenantService;
    private final PlatformPublishService publishService;

    @GetMapping("/youtube-kit")
    public ResponseEntity<YouTubeKitView> kit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return ResponseEntity.of(tenant(jwt).map(t -> publishService.kit(t.getId(), projectId)));
    }

    @PostMapping("/youtube-link")
    public ResponseEntity<MyShowcaseItemView> link(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
                                                   @Valid @RequestBody LinkPlatformFilmRequest request) {
        return tenant(jwt)
                .map(t -> ResponseEntity.status(HttpStatus.CREATED).body(publishService.link(t.getId(), projectId, request)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/official-upload")
    public ResponseEntity<OfficialUploadView> requestOfficialUpload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId,
                                                                    @Valid @RequestBody OfficialUploadRequest request) {
        return tenant(jwt)
                .map(t -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(publishService.requestOfficialUpload(t.getId(), projectId, request)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 404 until an upload was requested for this project. */
    @GetMapping("/official-upload")
    public ResponseEntity<OfficialUploadView> officialUpload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {
        return ResponseEntity.of(tenant(jwt).flatMap(t -> publishService.officialUpload(t.getId(), projectId)));
    }

    private Optional<Tenant> tenant(Jwt jwt) {
        return jwt == null ? Optional.empty() : tenantService.findByAdminUserId(jwt.getSubject());
    }
}
