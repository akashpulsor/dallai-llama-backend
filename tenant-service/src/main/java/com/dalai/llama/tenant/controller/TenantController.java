package com.dalai.llama.tenant.controller;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantUser;
import com.dalai.llama.tenant.dto.request.CreateTenantRequest;
import com.dalai.llama.tenant.dto.request.UpdateTenantRequest;
import com.dalai.llama.tenant.dto.response.*;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.repository.TenantUserRepository;
import com.dalai.llama.tenant.service.CredentialDeliveryService;
import com.dalai.llama.tenant.service.TenantAppService;
import com.dalai.llama.tenant.service.TenantService;
import com.dalai.llama.tenant.service.client.ProductServiceClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;
    private final TenantAppService tenantAppService;
    private final TenantAppRepository tenantAppRepository;
    private final TenantUserRepository tenantUserRepository;
    private final TenantRepository tenantRepository;
    private final ProductServiceClient productServiceClient;
    private final CredentialDeliveryService credentialDeliveryService;

    @PostMapping("/api/v1/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse create(
            @Valid @RequestBody CreateTenantRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return tenantService.createTenant(request, jwt);
    }

    @GetMapping({"/api/v1/tenants/me", "/api/v1/me"})
    @Transactional
    public ResponseEntity<MeResponse> getMe(@AuthenticationPrincipal Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        credentialDeliveryService.recordLogin(keycloakUserId);
        return ResponseEntity.ok(buildMeResponse(keycloakUserId, jwt));
    }

    @GetMapping("/api/v1/tenants/me/apps")
    public ResponseEntity<List<TenantAppSummary>> getMyApps(@AuthenticationPrincipal Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        return tenantService.findByAdminUserId(keycloakUserId)
                .map(tenant -> {
                    List<TenantAppSummary> apps = tenantAppRepository.findAllByTenantId(tenant.getId())
                            .stream().map(TenantAppSummary::from).toList();
                    return ResponseEntity.ok(apps);
                })
                .orElseGet(() -> ResponseEntity.ok(List.of()));
    }

    @GetMapping("/api/v1/tenants")
    public List<TenantResponse> list() {
        return tenantService.listTenants();
    }

    @GetMapping("/api/v1/tenants/{id}")
    public TenantResponse get(@PathVariable UUID id) {
        return tenantService.getTenant(id);
    }

    @GetMapping("/api/v1/tenants/{id}/subscriptions/{subscriptionId}")
    public SubscriptionDetailResponse getSubscriptionDetail(@PathVariable UUID id, @PathVariable UUID subscriptionId) {
        return tenantAppService.getSubscriptionDetails(id, subscriptionId);
    }

    @GetMapping("/api/v1/tenants/{id}/details")
    public TenantDetailResponse getDetails(@PathVariable UUID id) {
        return tenantService.getTenantDetails(id);
    }

    @PutMapping("/api/v1/tenants/{id}")
    public TenantResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantRequest request) {
        return tenantService.updateTenant(id, request);
    }

    @PostMapping("/api/v1/tenants/{id}/activate")
    public void activate(@PathVariable UUID id) {
        tenantService.activateTenant(id);
    }

    @PostMapping("/api/v1/tenants/{id}/suspend")
    public void suspend(@PathVariable UUID id, @RequestParam String reason) {
        tenantService.suspendTenant(id, reason);
    }

    @DeleteMapping("/api/v1/tenants/{id}")
    public void delete(@PathVariable UUID id,
                       @RequestParam(defaultValue = "Tenant deletion requested") String reason) {
        tenantService.deleteTenant(id, reason);
    }

    // ═══════════════════════════════════════════════════════════
    // DID MANAGEMENT — list + release DIDs from dashboard UI
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/api/v1/tenants/me/dids")
    public ResponseEntity<List<Map<String, Object>>> getMyDids(@AuthenticationPrincipal Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        return tenantService.findByAdminUserId(keycloakUserId)
                .map(tenant -> ResponseEntity.ok(productServiceClient.listDids(tenant.getId())))
                .orElseGet(() -> ResponseEntity.ok(List.of()));
    }

    @DeleteMapping("/api/v1/tenants/me/dids/{didId}")
    public ResponseEntity<Void> releaseDid(
            @PathVariable UUID didId,
            @AuthenticationPrincipal Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        return tenantService.findByAdminUserId(keycloakUserId)
                .map(tenant -> {
                    productServiceClient.releaseDid(tenant.getId(), didId);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ═══════════════════════════════════════════════════════════
    // SUBSCRIPTION MANAGEMENT — cancel subscription from dashboard UI
    // ═══════════════════════════════════════════════════════════

    @DeleteMapping("/api/v1/tenants/me/subscriptions/{subscriptionId}")
    public ResponseEntity<Void> cancelSubscription(
            @PathVariable UUID subscriptionId,
            @AuthenticationPrincipal Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        return tenantService.findByAdminUserId(keycloakUserId)
                .map(tenant -> {
                    // Verify the subscription belongs to this tenant via app
                    boolean owns = tenantAppRepository.existsByTenantIdAndSubscriptionId(
                            tenant.getId(), subscriptionId);
                    if (!owns) {
                        return ResponseEntity.status(403).<Void>build();
                    }
                    productServiceClient.cancelSubscription(subscriptionId);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private MeResponse buildMeResponse(String keycloakUserId, Jwt jwt) {
        TenantUser tenantUser = tenantUserRepository.findByKeycloakUserId(keycloakUserId).orElse(null);
        if (tenantUser != null) {
            Tenant tenant = tenantRepository.findById(tenantUser.getTenantId()).orElse(null);
            return buildMeResponse(tenant, tenantUser, jwt);
        }

        Tenant tenant = tenantService.findByAdminUserId(keycloakUserId).orElse(null);
        return buildMeResponse(tenant, null, jwt);
    }

    private MeResponse buildMeResponse(Tenant tenant, TenantUser tenantUser, Jwt jwt) {
        boolean hasTenant = tenant != null;
        String tenantSlug = tenant != null ? tenant.getSlug() : null;

        return MeResponse.builder()
                .tenantId(tenantUser != null ? tenantUser.getTenantId() : tenant != null ? tenant.getId() : null)
                .tenantSlug(tenantSlug)
                .slug(tenantSlug)
                .hasTenant(hasTenant)
                .needsOnboarding(!hasTenant)
                .isActive(tenant != null && tenant.isActive())
                .name(tenant != null ? tenant.getName() : null)
                .dashboardUrl(tenantSlug != null ? "https://admin-" + tenantSlug + ".dalaillama.in" : null)
                .status(tenant != null ? tenant.getStatus() : null)
                .statusMessage(tenant != null ? tenant.getStatusMessage() : null)
                .keycloakRealmName(tenant != null ? tenant.getKeycloakRealmName() : null)
                .tenantUserId(tenantUser != null ? tenantUser.getId() : null)
                .primaryRole(tenantUser != null ? tenantUser.getPrimaryRole().name() : extractPrimaryRole(jwt))
                .firstName(tenantUser != null ? tenantUser.getFirstName() : jwt.getClaimAsString("given_name"))
                .lastName(tenantUser != null ? tenantUser.getLastName() : jwt.getClaimAsString("family_name"))
                .email(tenantUser != null ? tenantUser.getEmail() : jwt.getClaimAsString("email"))
                .panels(extractPanels(tenant))
                .build();
    }

    @SuppressWarnings("unchecked")
    private String extractPrimaryRole(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null) {
            List<String> roles = (List<String>) realmAccess.get("roles");
            if (roles != null) {
                if (roles.contains("PLATFORM_ADMIN")) return "PLATFORM_ADMIN";
                if (roles.contains("TENANT_ADMIN")) return "ADMIN";
                if (roles.contains("SUPERVISOR")) return "SUPERVISOR";
                if (roles.contains("AGENT")) return "AGENT";
            }
        }
        return "UNKNOWN";
    }

    private List<String> extractPanels(Tenant tenant) {
        if (tenant == null || tenant.getAppPanels() == null) {
            return List.of();
        }
        return tenant.getAppPanels().stream()
                .map(panel -> panel.getAppType() != null ? panel.getAppType() : panel.getDisplayName())
                .toList();
    }
}
