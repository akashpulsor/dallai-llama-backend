package com.dalai.llama.tenant.leadmanagement.audience;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceDtos.AudienceView;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceDtos.CreateAudienceRequest;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceDtos.ImportReport;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceDtos.LeadPage;
import com.dalai.llama.tenant.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

/** The creator's audiences (rules 21–25): create, upload a CSV, browse leads, remove a lead from an
 * audience, discard a wrong contact point. Tenant from the JWT; under {@code /api/v1/tenants/me/**}. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants/me/outreach")
public class MyAudienceController {

    private final TenantService tenantService;
    private final AudienceService audiences;
    private final LeadImportService imports;

    @GetMapping("/audiences")
    public ResponseEntity<List<AudienceView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(tenant(jwt).map(t -> audiences.audiences(t.getId())));
    }

    @PostMapping("/audiences")
    public ResponseEntity<AudienceView> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateAudienceRequest request) {
        return tenant(jwt).map(t -> ResponseEntity.status(HttpStatus.CREATED).body(audiences.create(t.getId(), request.name())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/audiences/{audienceId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID audienceId) {
        Optional<Tenant> tenant = tenant(jwt);
        if (tenant.isEmpty()) return ResponseEntity.notFound().build();
        audiences.delete(tenant.get().getId(), audienceId);
        return ResponseEntity.noContent().build();
    }

    /** A CSV of up to 5,000 rows / 2 MB, multipart field {@code file}. */
    @PostMapping(path = "/audiences/{audienceId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportReport> upload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID audienceId,
                                               @RequestPart("file") MultipartFile file) {
        return ResponseEntity.of(tenant(jwt).map(t -> imports.importCsv(t.getId(), audienceId, file.getOriginalFilename(), bytes(file))));
    }

    @GetMapping("/audiences/{audienceId}/leads")
    public ResponseEntity<LeadPage> leads(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID audienceId,
                                          @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.of(tenant(jwt).map(t -> audiences.leads(t.getId(), audienceId, q, page)));
    }

    @DeleteMapping("/audiences/{audienceId}/leads/{leadId}")
    public ResponseEntity<Void> removeFromAudience(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID audienceId,
                                                   @PathVariable UUID leadId) {
        Optional<Tenant> tenant = tenant(jwt);
        if (tenant.isEmpty()) return ResponseEntity.notFound().build();
        audiences.removeFromAudience(tenant.get().getId(), audienceId, leadId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/leads/{leadId}/contact-points/{contactPointId}")
    public ResponseEntity<Void> discardContactPoint(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID leadId,
                                                    @PathVariable UUID contactPointId) {
        Optional<Tenant> tenant = tenant(jwt);
        if (tenant.isEmpty()) return ResponseEntity.notFound().build();
        audiences.discardContactPoint(tenant.get().getId(), leadId, contactPointId);
        return ResponseEntity.noContent().build();
    }

    private static byte[] bytes(MultipartFile file) {
        if (file.getSize() > LeadImportService.MAX_BYTES) throw new IllegalArgumentException("Upload a file of at most 2 MB");
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
