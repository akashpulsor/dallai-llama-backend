package com.dalai.llama.tenant.controller;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import com.dalai.llama.tenant.dto.AdminTenantSummary;
import com.dalai.llama.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Ops dashboard read + toggle endpoints for tenants. Under {@code /api/v1/internal/admin/**} so
 * the ingress auth wall (Istio AuthorizationPolicy + oauth2-proxy + dalai_admin role on
 * ops.dalaillama.in) is the perimeter -- the app itself does no extra role check here, same
 * pattern llm-gateway's AdminLlmJobController follows.
 *
 * <p>List returns newest-first: an operator triaging "who signed up today, whose Keycloak
 * bootstrap failed" wants recent tenants at the top, not alphabetical.
 *
 * <p>Activate/deactivate are operator-only toggles on the {@code status} column. There is no
 * separate subscription table today (see billing-service {@code BillingState} for the closest
 * analogue), so "deactivate" at the tenant level is the practical equivalent of "suspend a
 * subscription" -- it flips status to SUSPENDED, which downstream services already read to
 * refuse work for that tenant. When a real subscription model exists these endpoints should
 * delegate to it; wiring is intentionally small here so that migration is straightforward.
 */
@RestController
@RequestMapping("/api/v1/internal/admin/tenants")
@RequiredArgsConstructor
public class AdminTenantController {

    private final TenantRepository tenantRepository;

    @GetMapping
    public ResponseEntity<List<AdminTenantSummary>> list() {
        List<AdminTenantSummary> summaries = tenantRepository.findAll().stream()
                .sorted(Comparator.comparing(t -> t.getCreatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .map(AdminTenantSummary::from)
                .toList();
        return ResponseEntity.ok(summaries);
    }

    @PostMapping("/{tenantId}/activate")
    @Transactional
    public ResponseEntity<AdminTenantSummary> activate(@PathVariable UUID tenantId) {
        Tenant tenant = load(tenantId);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setStatusMessage(null);
        tenant.setSubstatus(null);
        tenant.setStatusChangedAt(OffsetDateTime.now());
        if (tenant.getActivatedAt() == null) {
            tenant.setActivatedAt(OffsetDateTime.now());
        }
        tenant.setSuspendedAt(null);
        tenant.setSuspensionReason(null);
        return ResponseEntity.ok(AdminTenantSummary.from(tenantRepository.save(tenant)));
    }

    @PostMapping("/{tenantId}/deactivate")
    @Transactional
    public ResponseEntity<AdminTenantSummary> deactivate(@PathVariable UUID tenantId,
                                                         @RequestBody(required = false) DeactivateRequest request) {
        Tenant tenant = load(tenantId);
        tenant.setStatus(TenantStatus.SUSPENDED);
        tenant.setStatusChangedAt(OffsetDateTime.now());
        tenant.setSuspendedAt(OffsetDateTime.now());
        String reason = request == null ? null : request.reason();
        tenant.setSuspensionReason(reason == null || reason.isBlank()
                ? "Suspended by operator via ops dashboard"
                : reason);
        return ResponseEntity.ok(AdminTenantSummary.from(tenantRepository.save(tenant)));
    }

    private Tenant load(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No tenant " + tenantId));
    }

    /** Optional body for deactivate: {"reason": "..."}. Free text; lands in
     * {@code tenants.suspension_reason}, later shown alongside status. */
    public record DeactivateRequest(String reason) {
    }
}
