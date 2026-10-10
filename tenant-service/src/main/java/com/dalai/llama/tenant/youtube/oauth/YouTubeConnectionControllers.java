package com.dalai.llama.tenant.youtube.oauth;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.TenantService;
import com.dalai.llama.tenant.youtube.oauth.YouTubeConnectionService.ConnectionView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;

/** The three HTTP faces of a YouTube connection (rules 31–33). */
public final class YouTubeConnectionControllers {

    private YouTubeConnectionControllers() {
    }

    public record AuthorizationStart(String authorizationUrl) {
    }

    /** The creator's own channel. Tenant from the JWT; under {@code /api/v1/tenants/me/**}. */
    @RestController
    @RequiredArgsConstructor
    @RequestMapping("/api/v1/tenants/me/youtube/connection")
    public static class MyYouTubeConnectionController {

        private final TenantService tenantService;
        private final YouTubeConnectionService connections;

        @GetMapping
        public ResponseEntity<ConnectionView> get(@AuthenticationPrincipal Jwt jwt) {
            return ResponseEntity.of(tenant(jwt).map(t -> connections.creatorView(t.getId())));
        }

        /** The page sends the browser to the returned Google URL; Google comes back to the public callback. */
        @PostMapping("/start")
        public ResponseEntity<AuthorizationStart> start(@AuthenticationPrincipal Jwt jwt) {
            return ResponseEntity.of(tenant(jwt).map(t -> new AuthorizationStart(connections.startForCreator(t.getId(), jwt.getSubject()))));
        }

        /** Revokes our access at Google and forgets the token. The channel stays your verified channel. */
        @DeleteMapping
        public ResponseEntity<Void> disconnect(@AuthenticationPrincipal Jwt jwt) {
            Optional<Tenant> tenant = tenant(jwt);
            if (tenant.isEmpty()) return ResponseEntity.notFound().build();
            connections.disconnectCreator(tenant.get().getId());
            return ResponseEntity.noContent().build();
        }

        private Optional<Tenant> tenant(Jwt jwt) {
            return jwt == null ? Optional.empty() : tenantService.findByAdminUserId(jwt.getSubject());
        }
    }

    /** Where Google sends the browser back. Public (own gateway prefix {@code /api/v1/public/youtube});
     * trusts only the single-use server-side state. */
    @RestController
    @RequiredArgsConstructor
    @RequestMapping("/api/v1/public/youtube/oauth")
    public static class PublicYouTubeOAuthController {

        private final YouTubeConnectionService connections;

        @GetMapping("/callback")
        public ResponseEntity<Void> callback(@RequestParam(required = false) String state,
                                             @RequestParam(required = false) String code,
                                             @RequestParam(required = false) String error) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(connections.complete(state, code, error))).build();
        }
    }

    /** Ops: the official Dalaillama channel. Under the ops-routed {@code /api/v1/internal/admin/tenants/**}. */
    @RestController
    @RequiredArgsConstructor
    @RequestMapping("/api/v1/internal/admin/tenants/youtube/platform-connection")
    public static class AdminYouTubeConnectionController {

        private final YouTubeConnectionService connections;

        @GetMapping
        public ResponseEntity<ConnectionView> get() {
            return ResponseEntity.ok(connections.platformView());
        }

        @PostMapping("/start")
        public ResponseEntity<AuthorizationStart> start() {
            return ResponseEntity.ok(new AuthorizationStart(connections.startForPlatform("ops")));
        }

        @DeleteMapping
        public ResponseEntity<Void> disconnect() {
            connections.disconnectPlatform();
            return ResponseEntity.noContent().build();
        }
    }
}
