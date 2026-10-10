package com.dalai.llama.tenant.leadmanagement.controller;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.leadmanagement.domain.entity.CreatorEmailIdentity;
import com.dalai.llama.tenant.leadmanagement.service.CreatorEmailIdentityService;
import com.dalai.llama.tenant.service.TenantService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Creator-facing (JWT-authenticated) view + rotate for their OWN email identity. There is no
 * free-form send: creators reach brands only through templates (CREATOR_SHOWCASE.md Phase Y,
 * rule 15), via {@code /api/v1/tenants/me/outreach/**}.
 * Under {@code /api/v1/tenants/me/email/*}, same self-service convention as
 * {@link com.dalai.llama.tenant.controller.TenantController#getMe}, so the
 * gateway VirtualService's {@code /api/v1/tenants} route already covers it and no chart
 * update is needed.
 *
 * <p>Tenant is resolved from the presented Keycloak JWT (sub -> admin user id -> tenant), so
 * a creator can never fetch or rotate another creator's password by URL manipulation. */
@Slf4j
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenants/me/email")
public class SelfServiceCreatorEmailController {

    private final TenantService tenantService;
    private final CreatorEmailIdentityService creatorEmailIdentityService;

    @GetMapping("/identity")
    public ResponseEntity<Map<String, Object>> myIdentity(@AuthenticationPrincipal Jwt jwt) {
        Tenant tenant = resolveTenant(jwt);
        if (tenant == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no tenant"));
        return creatorEmailIdentityService.findByTenant(tenant.getId())
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> {
                    // Auto-provision on first read for creators whose identity somehow wasn't
                    // backfilled (defensive; the backfill endpoint handles the bulk case).
                    CreatorEmailIdentity minted = creatorEmailIdentityService.provisionForCreator(
                            tenant.getId(), tenant.getName());
                    return ResponseEntity.ok(toResponse(minted));
                });
    }

    @PostMapping("/identity/rotate-password")
    public ResponseEntity<Map<String, Object>> rotate(@AuthenticationPrincipal Jwt jwt) {
        Tenant tenant = resolveTenant(jwt);
        if (tenant == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no tenant"));
        try {
            return ResponseEntity.ok(toResponse(creatorEmailIdentityService.rotatePassword(tenant.getId())));
        } catch (IllegalArgumentException notFound) {
            // Rotation is only meaningful once an identity exists. Provision first, then
            // rotate on the row we just wrote.
            creatorEmailIdentityService.provisionForCreator(tenant.getId(), tenant.getName());
            return ResponseEntity.ok(toResponse(creatorEmailIdentityService.rotatePassword(tenant.getId())));
        }
    }

    private Tenant resolveTenant(Jwt jwt) {
        if (jwt == null) return null;
        return tenantService.findByAdminUserId(jwt.getSubject()).orElse(null);
    }

    private Map<String, Object> toResponse(CreatorEmailIdentity id) {
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
