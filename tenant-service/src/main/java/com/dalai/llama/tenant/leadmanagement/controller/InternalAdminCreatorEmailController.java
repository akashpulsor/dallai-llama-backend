package com.dalai.llama.tenant.leadmanagement.controller;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.leadmanagement.domain.entity.CreatorEmailIdentity;
import com.dalai.llama.tenant.leadmanagement.service.CreatorEmailIdentityService;
import com.dalai.llama.tenant.repository.TenantRepository;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ops-only fetch for the creator email identity + its provisional login password. Used to
 * hand a creator their credentials on onboarding. Path lives under
 * {@code /api/v1/internal/admin/**} -- same convention as {@code AdminTenantController}, i.e.
 * mesh-internal, permitted by {@code SecurityConfig#internalFilterChain}, and NOT exposed via
 * the public gateway VirtualService ([[feedback-no-public-internal-paths]] rule). The ops
 * dashboard reaches it through its own oauth2-proxy pass-through, same as every other admin
 * endpoint. */
@Slf4j
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/admin/lead-management")
public class InternalAdminCreatorEmailController {

    private final CreatorEmailIdentityService creatorEmailIdentityService;
    private final TenantRepository tenantRepository;

    @GetMapping("/email/{tenantId}")
    public ResponseEntity<Map<String, Object>> getForTenant(@PathVariable UUID tenantId) {
        return creatorEmailIdentityService.findByTenant(tenantId)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** One-shot backfill for tenants that already existed BEFORE the identity-provisioning
     * Kafka hook shipped. Idempotent per-tenant via {@link
     * CreatorEmailIdentityService#provisionForCreator} (existing rows are returned as-is,
     * password not regenerated -- so re-running this endpoint is safe and will not rotate a
     * password already handed to a creator). */
    @PostMapping("/email/backfill")
    public ResponseEntity<Map<String, Object>> backfillAll() {
        List<Map<String, Object>> created = new ArrayList<>();
        List<Map<String, Object>> existing = new ArrayList<>();
        int failed = 0;
        for (Tenant tenant : tenantRepository.findAll()) {
            try {
                boolean alreadyThere = creatorEmailIdentityService.findByTenant(tenant.getId()).isPresent();
                CreatorEmailIdentity id = creatorEmailIdentityService.provisionForCreator(
                        tenant.getId(), tenant.getName());
                Map<String, Object> row = Map.of(
                        "tenantId", tenant.getId(),
                        "tenantName", tenant.getName() == null ? "" : tenant.getName(),
                        "email", id.getEmail(),
                        "password", id.getEmailPassword() == null ? "" : id.getEmailPassword()
                );
                (alreadyThere ? existing : created).add(row);
            } catch (RuntimeException e) {
                failed++;
                log.error("Backfill failed for tenant {}: {}", tenant.getId(), e.getMessage(), e);
            }
        }
        log.info("Creator email backfill: created={} existing={} failed={}",
                created.size(), existing.size(), failed);
        return ResponseEntity.ok(Map.of(
                "createdCount", created.size(),
                "existingCount", existing.size(),
                "failedCount", failed,
                "created", created,
                "existing", existing
        ));
    }

    private Map<String, Object> toResponse(CreatorEmailIdentity id) {
        // Password IS returned here on purpose -- this endpoint is the "hand it to the
        // creator" flow. Never mirror this shape into any public-facing response.
        return Map.of(
                "tenantId", id.getTenantId(),
                "email", id.getEmail(),
                "displayName", id.getDisplayName() == null ? "" : id.getDisplayName(),
                "password", id.getEmailPassword() == null ? "" : id.getEmailPassword(),
                "status", id.getStatus().name(),
                "createdAt", id.getCreatedAt()
        );
    }
}
