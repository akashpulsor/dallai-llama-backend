package com.dalai.llama.tenant.controller;

import com.dalai.llama.tenant.domain.entity.AppPanel;
import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import com.dalai.llama.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Public (unauthenticated) endpoint for UI bootstrap.
 *
 * The UI's useTenantAuth hook calls this BEFORE login to discover:
 *   - Tenant identity (id, slug, status, domain)
 *   - Keycloak realm + base URL + issuer (for OIDC login flow)
 *   - Available app panels with Keycloak client IDs and required roles
 *
 * Optional ?app= filter returns only the panel matching that UI:
 *   ?app=admin       → only the admin panel
 *   ?app=supervisor  → only the supervisor panel
 *   ?app=agent       → only the agent panel
 *
 * Path: GET /api/v1/public/tenant-config/{slug}[?app=admin|supervisor|agent]
 * No JWT required — slug is the public tenant identifier.
 *
 * Status codes:
 *   200 OK            — tenant found and accessible, config returned
 *   400 Bad Request   — slug or app parameter malformed
 *   403 Forbidden     — tenant exists but is DELETED or SUSPENDED
 *   404 Not Found     — no tenant for that slug
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
public class PublicTenantConfigController {

    private final TenantRepository tenantRepository;

    @Value("${keycloak.public-url:http://auth.localhost:8081}")
    private String defaultKeycloakUrl;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    private static final Pattern SLUG_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9-]{0,48}[a-z0-9]$");

    private static final Set<String> ALLOWED_APP_FILTERS =
            Set.of("admin", "supervisor", "agent");

    /** Maps the query-string value to the AppPanel.appType stored in DB. */
    private static final Map<String, String> APP_FILTER_TO_APP_TYPE = Map.of(
            "admin",      "ADMIN_PANEL",
            "supervisor", "SUPERVISOR",
            "agent",      "CONTACT_CENTER"
    );

    @GetMapping("/tenant-config/{slug}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getTenantConfig(
            @PathVariable String slug,
            @RequestParam(value = "app", required = false) String appFilter
    ) {
        // 1) Validate slug format BEFORE the DB lookup
        if (slug == null || slug.length() < 2 || slug.length() > 50 || !SLUG_PATTERN.matcher(slug).matches()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_slug",
                    "message", "Slug must be 2-50 lowercase alphanumeric characters or hyphens"
            ));
        }

        // 2) Validate app filter if provided
        String normalizedFilter = null;
        if (appFilter != null && !appFilter.isBlank()) {
            normalizedFilter = appFilter.toLowerCase(Locale.ROOT).trim();
            if (!ALLOWED_APP_FILTERS.contains(normalizedFilter)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "invalid_app",
                        "message", "app must be one of: admin, supervisor, agent",
                        "received", appFilter
                ));
            }
        }

        // 3) Lookup tenant
        Optional<Tenant> tenantOpt = tenantRepository.findBySlug(slug);
        if (tenantOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Tenant tenant = tenantOpt.get();
        if (tenant.getStatus() == TenantStatus.DELETED || tenant.getStatus() == TenantStatus.SUSPENDED) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "tenant_inactive",
                    "message", "Tenant is not available",
                    "status", tenant.getStatus().name()
            ));
        }

        return ResponseEntity.ok(buildConfig(tenant, normalizedFilter));
    }

    private Map<String, Object> buildConfig(Tenant tenant, String appFilter) {
        Map<String, Object> config = new LinkedHashMap<>();

        // ── Identity ──
        config.put("tenant_id", tenant.getId().toString());
        config.put("slug", tenant.getSlug());
        config.put("status", tenant.getStatus().name());
        config.put("domain", tenant.getSlug() + "." + baseDomain);

        // ── Keycloak (prefer values stamped during provisioning) ──
        String realmName = tenant.getKeycloakRealmName();
        String kcUrl = tenant.getKeycloakUrl() != null ? tenant.getKeycloakUrl() : defaultKeycloakUrl;
        String kcIssuer = tenant.getKeycloakIssuer() != null
                ? tenant.getKeycloakIssuer()
                : kcUrl + "/realms/" + realmName;

        config.put("keycloak_url", kcUrl);
        config.put("keycloak_realm", realmName);
        config.put("keycloak_issuer", kcIssuer);

        // ── App panels (filtered in controller, not DB) ──
        List<AppPanel> allPanels = tenant.getAppPanels() != null
                ? tenant.getAppPanels()
                : List.of();

        if (appFilter != null) {
            String targetAppType = APP_FILTER_TO_APP_TYPE.get(appFilter);
            AppPanel match = allPanels.stream()
                    .filter(p -> Objects.equals(p.getAppType(), targetAppType))
                    .findFirst()
                    .orElse(null);

            if (match != null) {
                config.put("app", match);
            } else {
                config.put("app", null);
                log.debug("No panel of type {} provisioned for tenant {}", targetAppType, tenant.getSlug());
            }
        } else {
            config.put("apps", allPanels);
        }

        return config;
    }
}