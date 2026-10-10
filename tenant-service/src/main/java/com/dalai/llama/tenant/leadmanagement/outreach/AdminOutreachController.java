package com.dalai.llama.tenant.leadmanagement.outreach;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ops: stop all mail to an address (bounce, complaint, request by phone), import the brand
 * directory, and run automatic picks or the digest now instead of waiting for their schedule.
 * Under the ops-routed {@code /api/v1/internal/admin/tenants/**}. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/admin/tenants/outreach")
public class AdminOutreachController {

    private final OutreachService outreachService;
    private final DailyDigestJob digestJob;
    private final AutoPickJob autoPickJob;
    private final com.dalai.llama.tenant.leadmanagement.brand.BrandDirectoryService directory;

    public record SuppressRequest(@NotBlank @Email String email) {
    }

    @PostMapping("/suppressions")
    public ResponseEntity<Void> suppress(@Valid @RequestBody SuppressRequest request) {
        outreachService.suppressByOps(request.email());
        return ResponseEntity.noContent().build();
    }

    /** Brand directory CSV (text/csv body): email,name,company,industry,country,website. */
    @PostMapping(path = "/brands/import", consumes = {"text/csv", "text/plain"})
    public ResponseEntity<com.dalai.llama.tenant.leadmanagement.brand.BrandDirectoryService.ImportResult> importBrands(
            @RequestBody String csv, @org.springframework.web.bind.annotation.RequestParam(defaultValue = "true") boolean autoPicks) {
        return ResponseEntity.ok(directory.importCsv(csv, autoPicks));
    }

    /** Queue automatic picks and follower notices now (normally 12:30 UTC, before the digest). */
    @PostMapping("/auto-picks/run")
    public ResponseEntity<AutoPickJob.RunSummary> runAutoPicks() {
        return ResponseEntity.ok(autoPickJob.run(java.time.Instant.now().minus(java.time.Duration.ofDays(1))));
    }

    @PostMapping("/digest/run")
    public ResponseEntity<DailyDigestJob.RunSummary> runDigest() {
        return ResponseEntity.ok(digestJob.run());
    }
}
