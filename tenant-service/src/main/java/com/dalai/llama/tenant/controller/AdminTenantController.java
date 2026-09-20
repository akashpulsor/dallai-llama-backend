package com.dalai.llama.tenant.controller;

import com.dalai.llama.tenant.dto.AdminTenantSummary;
import com.dalai.llama.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * Ops dashboard read endpoints for tenants. Under {@code /api/v1/internal/admin/**} so the
 * ingress auth wall (Istio AuthorizationPolicy + oauth2-proxy + dalai_admin role on
 * ops.dalaillama.in) is the perimeter -- the app itself does no extra role check here, same
 * pattern llm-gateway's AdminLlmJobController follows.
 *
 * <p>List returns newest-first: an operator triaging "who signed up today, whose Keycloak
 * bootstrap failed" wants recent tenants at the top, not alphabetical.
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
}
