package com.dalai.llama.tenant.youtube.controller;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.TenantService;
import com.dalai.llama.tenant.youtube.dto.ChannelLinkView;
import com.dalai.llama.tenant.youtube.dto.LinkChannelRequest;
import com.dalai.llama.tenant.youtube.dto.YouTubeVideoView;
import com.dalai.llama.tenant.youtube.service.ChannelImportService;
import com.dalai.llama.tenant.youtube.service.ChannelLinkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/** The creator's YouTube channel: link it, verify it, re-sync it, list its videos. Under
 * {@code /api/v1/tenants/me/**}, already routed by the gateway; tenant from the JWT only. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants/me/youtube")
public class MyYouTubeController {

    private final TenantService tenantService;
    private final ChannelLinkService linkService;
    private final ChannelImportService importService;

    /** 404 when no channel has been added yet. */
    @GetMapping("/channel")
    public ResponseEntity<ChannelLinkView> channel(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(tenant(jwt).flatMap(t -> linkService.current(t.getId())));
    }

    @PostMapping("/channel")
    public ResponseEntity<ChannelLinkView> link(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody LinkChannelRequest request) {
        return ResponseEntity.of(tenant(jwt).map(t -> linkService.start(t.getId(), request.channel())));
    }

    /** 409 with an explanation while the code isn't in the description yet. */
    @PostMapping("/channel/verify")
    public ResponseEntity<ChannelLinkView> verify(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(tenant(jwt).map(t -> linkService.verify(t.getId())));
    }

    @PostMapping("/sync")
    public ResponseEntity<List<YouTubeVideoView>> sync(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(tenant(jwt).map(t -> {
            importService.resync(t.getId());
            return importService.listVideos(t.getId());
        }));
    }

    @GetMapping("/videos")
    public ResponseEntity<List<YouTubeVideoView>> videos(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(tenant(jwt).map(t -> importService.listVideos(t.getId())));
    }

    private Optional<Tenant> tenant(Jwt jwt) {
        return jwt == null ? Optional.empty() : tenantService.findByAdminUserId(jwt.getSubject());
    }
}
