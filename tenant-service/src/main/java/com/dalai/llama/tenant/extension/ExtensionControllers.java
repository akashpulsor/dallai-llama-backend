package com.dalai.llama.tenant.extension;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.extension.ExtensionTokenService.ExtensionPrincipal;
import com.dalai.llama.tenant.extension.ExtensionTokenService.MintedToken;
import com.dalai.llama.tenant.extension.ExtensionTokenService.TokenView;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.service.TenantService;
import com.dalai.llama.tenant.showcase.domain.ShowcaseFormat;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.dto.MyShowcaseItemView;
import com.dalai.llama.tenant.showcase.dto.PickVideoRequest;
import com.dalai.llama.tenant.showcase.service.ShowcasePickService;
import com.dalai.llama.tenant.youtube.client.YouTubeVideoUrl;
import com.dalai.llama.tenant.youtube.oauth.YouTubeConnectionService;
import com.dalai.llama.tenant.youtube.publish.PublishDtos.JobView;
import com.dalai.llama.tenant.youtube.publish.PublishDtos.PublishRequest;
import com.dalai.llama.tenant.youtube.publish.PublishDtos.PublishableFilm;
import com.dalai.llama.tenant.youtube.publish.YouTubePublishService;
import com.dalai.llama.tenant.youtube.repository.YouTubeVideoRepository;
import com.dalai.llama.tenant.youtube.service.ChannelImportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The Chrome extension's two faces (rules 38–39). */
public final class ExtensionControllers {

    public static final String HEADER = "X-Dalai-Extension-Token";

    private ExtensionControllers() {
    }

    /** The web app pairs and unpairs browsers. Tenant from the JWT; under {@code /api/v1/tenants/me/**}. */
    @RestController
    @RequiredArgsConstructor
    @RequestMapping("/api/v1/tenants/me/extension/tokens")
    public static class MyExtensionTokenController {

        private final TenantService tenantService;
        private final ExtensionTokenService tokens;

        public record PairRequest(@Size(max = 80) String label) {
        }

        /** Returns the token once; the page passes it straight to the extension and forgets it. */
        @PostMapping
        public ResponseEntity<MintedToken> pair(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody(required = false) PairRequest request) {
            return tenant(jwt).map(t -> ResponseEntity.status(HttpStatus.CREATED)
                            .body(tokens.mint(t.getId(), jwt.getSubject(), request == null ? null : request.label())))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        }

        @GetMapping
        public ResponseEntity<List<TokenView>> list(@AuthenticationPrincipal Jwt jwt) {
            return ResponseEntity.of(tenant(jwt).map(t -> tokens.list(t.getId())));
        }

        @DeleteMapping("/{tokenId}")
        public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tokenId) {
            Optional<Tenant> tenant = tenant(jwt);
            if (tenant.isEmpty()) return ResponseEntity.notFound().build();
            tokens.revoke(tenant.get().getId(), tokenId);
            return ResponseEntity.noContent().build();
        }

        private Optional<Tenant> tenant(Jwt jwt) {
            return jwt == null ? Optional.empty() : tenantService.findByAdminUserId(jwt.getSubject());
        }
    }

    /** What the extension may do, and nothing else (rule 39). Own gateway prefix; no JWT; the
     * extension token decides the tenant. */
    @RestController
    @RequiredArgsConstructor
    @RequestMapping("/api/v1/extension")
    public static class ExtensionApiController {

        private final ExtensionTokenService tokens;
        private final TenantRepository tenants;
        private final YouTubeConnectionService connections;
        private final ShowcasePickService picks;
        private final YouTubeVideoRepository videos;
        private final ChannelImportService imports;
        private final YouTubePublishService publishing;

        public record Me(String creatorName, boolean channelConnected, String channelTitle, boolean canPublish) {
        }

        public record ImportRequest(
                /* A YouTube watch/shorts/youtu.be link or a bare video id. */
                @NotBlank @Size(max = 200) String video,
                @NotNull ShowcaseIndustry industry,
                @NotNull ShowcaseFormat format,
                @Size(max = 80) String clientLabel,
                @AssertTrue(message = "confirm you may show this video publicly") boolean rightsConfirmed
        ) {
        }

        @GetMapping("/me")
        public Me me(@RequestHeader(value = HEADER, required = false) String token) {
            ExtensionPrincipal p = tokens.require(token);
            var view = connections.creatorView(p.tenantId());
            String name = tenants.findById(p.tenantId()).map(Tenant::getName).orElse("");
            return new Me(name, view.connected(), view.channelTitle(), view.connected() && view.canPublish());
        }

        /** Puts the YouTube video open in the browser on the creator's portfolio; it must be on their
         * verified channel. A brand-new upload is fetched first. */
        @PostMapping("/portfolio/import")
        public ResponseEntity<MyShowcaseItemView> importVideo(@RequestHeader(value = HEADER, required = false) String token,
                                                              @Valid @RequestBody ImportRequest r) {
            ExtensionPrincipal p = tokens.require(token);
            String videoId = YouTubeVideoUrl.videoId(r.video())
                    .orElseThrow(() -> new IllegalArgumentException("That isn't a YouTube video link"));
            if (videos.findById(videoId).isEmpty()) imports.resync(p.tenantId());
            return ResponseEntity.status(HttpStatus.CREATED).body(picks.pick(p.tenantId(),
                    new PickVideoRequest(videoId, r.industry(), r.format(), r.clientLabel(), null, r.rightsConfirmed())));
        }

        @GetMapping("/films")
        public List<PublishableFilm> films(@RequestHeader(value = HEADER, required = false) String token) {
            return publishing.publishableFilms(tokens.require(token).tenantId());
        }

        /** Same rules as the web app: public needs confirmPublic, non-private needs client consent. */
        @PostMapping("/publish")
        public ResponseEntity<JobView> publish(@RequestHeader(value = HEADER, required = false) String token,
                                               @Valid @RequestBody PublishRequest request) {
            ExtensionPrincipal p = tokens.require(token);
            return ResponseEntity.status(HttpStatus.CREATED).body(publishing.publish(p.tenantId(), p.subject(), request, "EXTENSION"));
        }

        @GetMapping("/publish-jobs")
        public List<JobView> jobs(@RequestHeader(value = HEADER, required = false) String token) {
            return publishing.jobs(tokens.require(token).tenantId());
        }
    }
}
