package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.leadmanagement.outreach.EmailTemplateService.TemplateRequest;
import com.dalai.llama.tenant.leadmanagement.outreach.EmailTemplateService.TemplateView;
import com.dalai.llama.tenant.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Rule 27: the templates a creator can send (global ones from Dalai Llama plus their own), and
 * their own template edits. Globals are read-only here. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants/me/outreach/templates")
public class MyEmailTemplateController {

    private final TenantService tenantService;
    private final EmailTemplateService templates;

    @GetMapping
    public ResponseEntity<List<TemplateView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(tenant(jwt).map(t -> templates.usableBy(t.getId())));
    }

    @PostMapping
    public ResponseEntity<TemplateView> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TemplateRequest request) {
        return tenant(jwt).map(t -> ResponseEntity.status(HttpStatus.CREATED).body(templates.create(t.getId(), request)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{templateId}")
    public ResponseEntity<TemplateView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID templateId,
                                               @Valid @RequestBody TemplateRequest request) {
        return ResponseEntity.of(tenant(jwt).map(t -> templates.update(t.getId(), templateId, request)));
    }

    /** Deactivates: past sends keep their template for analytics. */
    @DeleteMapping("/{templateId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID templateId) {
        Optional<Tenant> tenant = tenant(jwt);
        if (tenant.isEmpty()) return ResponseEntity.notFound().build();
        templates.deactivate(tenant.get().getId(), templateId);
        return ResponseEntity.noContent().build();
    }

    private Optional<Tenant> tenant(Jwt jwt) {
        return jwt == null ? Optional.empty() : tenantService.findByAdminUserId(jwt.getSubject());
    }
}
