package com.dalai.llama.tenant.youtube.publish;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.TenantService;
import com.dalai.llama.tenant.youtube.publish.PublishDtos.EditRequest;
import com.dalai.llama.tenant.youtube.publish.PublishDtos.JobView;
import com.dalai.llama.tenant.youtube.publish.PublishDtos.PublishRequest;
import com.dalai.llama.tenant.youtube.publish.PublishDtos.PublishableFilm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Publishing to the creator's own channel and its analytics (rules 34–37). Tenant from the JWT;
 * under {@code /api/v1/tenants/me/**}. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants/me/youtube")
public class MyYouTubePublishController {

    private final TenantService tenantService;
    private final YouTubePublishService publishing;
    private final YouTubeAnalyticsService analytics;

    @GetMapping("/films")
    public ResponseEntity<List<PublishableFilm>> films(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(tenant(jwt).map(t -> publishing.publishableFilms(t.getId())));
    }

    @GetMapping("/publish-jobs")
    public ResponseEntity<List<JobView>> jobs(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(tenant(jwt).map(t -> publishing.jobs(t.getId())));
    }

    @GetMapping("/publish-jobs/{jobId}")
    public ResponseEntity<JobView> job(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
        return ResponseEntity.of(tenant(jwt).map(t -> publishing.job(t.getId(), jobId)));
    }

    /** Queues an upload. Same {@code idempotencyKey} = same job, never a second upload. */
    @PostMapping("/publish-jobs")
    public ResponseEntity<JobView> publish(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PublishRequest request) {
        return tenant(jwt).map(t -> ResponseEntity.status(HttpStatus.CREATED).body(publishing.publish(t.getId(), jwt.getSubject(), request, "WEB")))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/publish-jobs/{jobId}")
    public ResponseEntity<JobView> edit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId, @Valid @RequestBody EditRequest request) {
        return ResponseEntity.of(tenant(jwt).map(t -> publishing.edit(t.getId(), jobId, request)));
    }

    @PutMapping(path = "/publish-jobs/{jobId}/thumbnail", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobView> thumbnail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId, @RequestPart("file") MultipartFile file) {
        return ResponseEntity.of(tenant(jwt).map(t -> publishing.setThumbnail(t.getId(), jobId, bytes(file), file.getContentType())));
    }

    @PostMapping("/publish-jobs/{jobId}/cancel")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
        Optional<Tenant> tenant = tenant(jwt);
        if (tenant.isEmpty()) return ResponseEntity.notFound().build();
        publishing.cancel(tenant.get().getId(), jobId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/publish-jobs/{jobId}/retry")
    public ResponseEntity<JobView> retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
        return ResponseEntity.of(tenant(jwt).map(t -> publishing.retry(t.getId(), jobId)));
    }

    /** Rule 37: live from YouTube Analytics (7–90 days, cached an hour). */
    @GetMapping("/analytics")
    public ResponseEntity<YouTubeAnalyticsService.Report> analytics(@AuthenticationPrincipal Jwt jwt,
                                                                    @RequestParam(defaultValue = "28") int days) {
        return ResponseEntity.of(tenant(jwt).map(t -> analytics.report(t.getId(), days)));
    }

    private static byte[] bytes(MultipartFile file) {
        if (file.getSize() > YouTubePublishService.MAX_THUMBNAIL_BYTES) throw new IllegalArgumentException("A thumbnail must be at most 2 MB");
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<Tenant> tenant(Jwt jwt) {
        return jwt == null ? Optional.empty() : tenantService.findByAdminUserId(jwt.getSubject());
    }
}
