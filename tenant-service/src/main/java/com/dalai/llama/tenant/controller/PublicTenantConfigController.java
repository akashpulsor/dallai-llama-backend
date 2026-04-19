package com.dalai.llama.tenant.controller;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.dalai.llama.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Public (unauthenticated) endpoint for UI bootstrap.
 *
 * The UI's useTenantAuth hook calls this BEFORE login to discover:
 *   - Keycloak realm + client ID (for OIDC login flow)
 *   - SIP/WebSocket/TURN URLs (for WebRTC softphone init)
 *   - Feature flags (for conditional UI rendering)
 *   - Tenant display name & status
 *
 * Path: GET /api/v1/public/tenant-config/{slug}
 * No JWT required — slug is the public tenant identifier.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
public class PublicTenantConfigController {

    private final TenantRepository tenantRepository;
    private final TenantAppRepository tenantAppRepository;

    @org.springframework.beans.factory.annotation.Value("${keycloak.admin.url:http://auth.localhost:8081}")
    private String keycloakUrl;

    @org.springframework.beans.factory.annotation.Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @GetMapping("/tenant-config/{slug}")
    public ResponseEntity<Map<String, Object>> getTenantConfig(@PathVariable String slug) {
        Optional<Tenant> tenantOpt = tenantRepository.findBySlug(slug);
        if (tenantOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Tenant tenant = tenantOpt.get();
        if (!tenant.isActive() && !tenant.getStatus().isProvisioning()) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "tenant_inactive",
                    "message", "Tenant is not active",
                    "status", tenant.getStatus().name()
            ));
        }

        // Find the primary (first enabled) tenant app
        List<TenantApp> apps = tenantAppRepository.findByTenantIdAndEnabledTrueOrderByDisplayOrderAsc(tenant.getId());

        Map<String, Object> config = new LinkedHashMap<>();

        // ── Tenant identity ──
        config.put("tenant_id", tenant.getId());
        config.put("name", tenant.getName());
        config.put("slug", tenant.getSlug());
        config.put("company_name", tenant.getCompanyName());
        config.put("timezone", tenant.getTimezone());
        config.put("country", tenant.getCountry());
        config.put("status", tenant.getStatus().name());

        // ── Keycloak auth config (UI needs this for OIDC login) ──
        String realmName = tenant.getKeycloakRealmName(); // e.g. "tenant-{uuid}"
        config.put("keycloak_url", keycloakUrl);            // e.g. "https://auth.dalaillama.in"
        config.put("keycloak_realm", realmName);
        config.put("keycloak_issuer", keycloakUrl + "/realms/" + realmName);
        config.put("domain", tenant.getSlug() + "." + baseDomain);

        // ── Per-app configs ──
        List<Map<String, Object>> appConfigs = new ArrayList<>();
        for (TenantApp app : apps) {
            Map<String, Object> appCfg = new LinkedHashMap<>();
            appCfg.put("app_id", app.getId());
            appCfg.put("app_type", app.getAppType() != null ? app.getAppType().name() : null);
            appCfg.put("product_code", app.getProductCode());
            appCfg.put("plan_tier", app.getPlanTier());
            appCfg.put("display_name", app.getDisplayName());
            appCfg.put("subdomain", app.getSubdomain());
            appCfg.put("keycloak_client_id", app.getKeycloakClientId());
            appCfg.put("dashboard_url", app.getDashboardUrl());

            // SIP/WebRTC endpoints
            appCfg.put("sip_domain", app.getSipEndpointDomain());
            appCfg.put("sip_udp_url", app.getSipUdpUrl());
            appCfg.put("sip_tls_url", app.getSipTlsUrl());
            appCfg.put("websocket_url", app.getWebsocketUrl());
            appCfg.put("turn_url", app.getTurnUrl());

            // Feature flags (UI renders conditionally)
            Map<String, Boolean> features = new LinkedHashMap<>();
            features.put("recording", Boolean.TRUE.equals(app.getRecordingEnabled()));
            features.put("ai_transcription", Boolean.TRUE.equals(app.getAiTranscriptionEnabled()));
            features.put("ai_sentiment", Boolean.TRUE.equals(app.getAiSentimentEnabled()));
            features.put("ai_bot", Boolean.TRUE.equals(app.getAiBotEnabled()));
            features.put("ai_agent_assist", Boolean.TRUE.equals(app.getAiAgentAssistEnabled()));
            features.put("barge", Boolean.TRUE.equals(app.getBargeEnabled()));
            features.put("whisper", Boolean.TRUE.equals(app.getWhisperEnabled()));
            features.put("listen", Boolean.TRUE.equals(app.getListenEnabled()));
            features.put("voicemail", Boolean.TRUE.equals(app.getVoicemailEnabled()));
            features.put("crm_integration", Boolean.TRUE.equals(app.getCrmIntegrationEnabled()));
            features.put("progressive_dialer", Boolean.TRUE.equals(app.getProgressiveDialerEnabled()));
            features.put("predictive_dialer", Boolean.TRUE.equals(app.getPredictiveDialerEnabled()));
            features.put("conversational_ivr", Boolean.TRUE.equals(app.getConversationalIvrEnabled()));
            features.put("advanced_reporting", Boolean.TRUE.equals(app.getAdvancedReportingEnabled()));
            appCfg.put("features", features);

            // Capacity
            Map<String, Object> capacity = new LinkedHashMap<>();
            capacity.put("max_agents", app.getMaxAgents());
            capacity.put("max_channels", app.getMaxChannels());
            capacity.put("max_queues", app.getMaxQueues());
            capacity.put("max_dids", app.getMaxDids());
            appCfg.put("capacity", capacity);

            appConfigs.add(appCfg);
        }
        config.put("apps", appConfigs);

        return ResponseEntity.ok(config);
    }
}
