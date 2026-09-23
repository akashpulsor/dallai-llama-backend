package com.dalai.llama.tenant.leadmanagement.controller;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.leadmanagement.domain.entity.CreatorEmailIdentity;
import com.dalai.llama.tenant.leadmanagement.email.CreatorEmailMessage;
import com.dalai.llama.tenant.leadmanagement.email.CreatorEmailSender;
import com.dalai.llama.tenant.leadmanagement.email.EmailSendResult;
import com.dalai.llama.tenant.leadmanagement.service.CreatorEmailIdentityService;
import com.dalai.llama.tenant.service.TenantService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Creator-facing (JWT-authenticated) view + rotate + send for their OWN email identity.
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
    private final CreatorEmailSender emailSender;

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

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SendRequest req) {
        Tenant tenant = resolveTenant(jwt);
        if (tenant == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no tenant"));

        EmailSendResult result = emailSender.send(CreatorEmailMessage.builder()
                .fromCreatorId(tenant.getId())
                .to(req.to())
                .subject(req.subject())
                .bodyText(req.bodyText())
                .bodyHtml(req.bodyHtml())
                .replyTo(null)
                .build());

        if (result.accepted()) {
            return ResponseEntity.accepted().body(Map.of(
                    "accepted", true,
                    "providerMessageId", result.providerMessageId() == null ? "" : result.providerMessageId()));
        }
        // A "not configured" reject reads as SERVICE_UNAVAILABLE, everything else 502 -- lets
        // the UI distinguish "setup incomplete" (show admin CTA) from "transient send error".
        String err = result.error() == null ? "unknown" : result.error();
        HttpStatus status = err.contains("not configured") ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(Map.of("accepted", false, "error", err));
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

    public record SendRequest(
            @NotEmpty List<@Size(min = 3, max = 254) String> to,
            @Size(max = 300) String subject,
            @Size(max = 200_000) String bodyText,
            @Size(max = 500_000) String bodyHtml
    ) {}
}
