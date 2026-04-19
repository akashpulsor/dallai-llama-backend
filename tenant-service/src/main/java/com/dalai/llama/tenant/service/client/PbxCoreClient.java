package com.dalai.llama.tenant.service.client;

import com.dalai.llama.tenant.dto.request.*;
import com.dalai.llama.tenant.dto.response.TenantTelecomEndpoints;
import com.dalai.llama.tenant.dto.response.TurnCredentialsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PbxCoreClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${dalaillama.pbx-core.url:http://pbx-core.apps.svc.cluster.local:8080}")
    private String pbxCoreUrl;

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PROVISION_TIMEOUT = Duration.ofSeconds(60);

    private WebClient client() {
        return webClientBuilder.baseUrl(pbxCoreUrl).build();
    }

    // ════════════════════════════════════════════════════════════════
    // RTPEngine
    // ════════════════════════════════════════════════════════════════

    /**
     * Store RTPEngine config. PBX-Core saves to Redis.
     * Kamailio calls PBX-Core at runtime to get per-tenant RTPEngine flags.
     */
    public void configureRtpEngine(RtpEngineConfigRequest request) {
        log.info("PBX-Core: storing RTPEngine config for tenant {}", request.getTenantId());
        try {
            client().post()
                    .uri("/api/v1/provisioning/tenants/{tenantId}/rtpengine", request.getTenantId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("PBX-Core RTPEngine config failed for {}: {}",
                    request.getTenantId(), e.getResponseBodyAsString());
            throw new PbxCoreException("RTPEngine configuration failed: " + e.getMessage(), e);
        }
    }

    public void removeRtpEngineConfig(String tenantId) {
        log.info("PBX-Core: removing RTPEngine config for tenant {}", tenantId);
        try {
            client().delete()
                    .uri("/api/v1/provisioning/tenants/{tenantId}/rtpengine", tenantId)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("PBX-Core RTPEngine config removal failed for {}: {}", tenantId, e.getMessage());
        }
    }
    // ════════════════════════════════════════════════════════════════
    // Kamailio Provisioning
    // ════════════════════════════════════════════════════════════════

    /**
     * Full Kamailio provisioning — subscriber, domain, dialplan, dispatcher,
     * channel limits. PBX-Core handles kamcmd reload internally.
     */
    public TenantTelecomEndpoints provisionKamailio(KamailioProvisioningRequest request) {
        log.info("PBX-Core: provisioning Kamailio for tenant {}", request.getTenantId());
        try {
            return client().post()
                    .uri("/api/v1/provisioning/tenants/{tenantId}/kamailio", request.getTenantId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(TenantTelecomEndpoints.class)
                    .timeout(PROVISION_TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("PBX-Core Kamailio provisioning failed for {}: {} {}",
                    request.getTenantId(), e.getStatusCode(), e.getResponseBodyAsString());
            throw new PbxCoreException("Kamailio provisioning failed: " + e.getMessage(), e);
        }
    }

    /**
     * Remove all Kamailio config for a subscription.
     */
    public void deprovisionKamailio(UUID subscriptionId) {
        log.info("PBX-Core: deprovisioning Kamailio for subscription {}", subscriptionId);
        try {
            client().delete()
                    .uri("/api/v1/provisioning/subscriptions/{subscriptionId}/kamailio", subscriptionId)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("PBX-Core Kamailio deprovision failed for {}: {}",
                    subscriptionId, e.getResponseBodyAsString());
            throw new PbxCoreException("Kamailio deprovisioning failed: " + e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // FreeSWITCH Dialplan
    // ════════════════════════════════════════════════════════════════

    /**
     * Store generated dialplan. PBX-Core saves to DB and serves
     * via mod_xml_curl when FreeSWITCH asks at call time.
     */
    public void storeFreeSwitchDialplan(FreeSwitchDialplanRequest request) {
        log.info("PBX-Core: storing FreeSWITCH dialplan for tenant {} [context={}]",
                request.getTenantId(), request.getContext());
        try {
            client().post()
                    .uri("/api/v1/provisioning/tenants/{tenantId}/freeswitch/dialplan", request.getTenantId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("PBX-Core dialplan store failed for {}: {}",
                    request.getTenantId(), e.getResponseBodyAsString());
            throw new PbxCoreException("FreeSWITCH dialplan storage failed: " + e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // CoTURN
    // ════════════════════════════════════════════════════════════════

    /**
     * Configure TURN credentials. PBX-Core generates HMAC creds
     * (owns CoTURN secret), stores in Redis, returns URLs.
     */
    public TurnCredentialsResponse configureTurn(TurnConfigRequest request) {
        log.info("PBX-Core: configuring TURN for tenant {}", request.getTenantId());
        try {
            return client().post()
                    .uri("/api/v1/provisioning/tenants/{tenantId}/turn", request.getTenantId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(TurnCredentialsResponse.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("PBX-Core TURN config failed for {}: {}",
                    request.getTenantId(), e.getResponseBodyAsString());
            throw new PbxCoreException("TURN configuration failed: " + e.getMessage(), e);
        }
    }

    /**
     * Remove TURN credentials for tenant.
     */
    public void removeTurnConfig(String tenantSlug) {
        log.info("PBX-Core: removing TURN config for {}", tenantSlug);
        try {
            client().delete()
                    .uri("/api/v1/provisioning/tenants/{slug}/turn", tenantSlug)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("PBX-Core TURN removal failed for {}: {}", tenantSlug, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // AI Service Config
    // ════════════════════════════════════════════════════════════════

    /**
     * Store resolved AI config. PBX-Core puts flat DTO fields into Redis hash.
     * AI Service reads at runtime via GET /internal/ai/config/{tenantId}.
     */
    public void configureAi(AiConfigRequest request) {
        log.info("PBX-Core: storing AI config for tenant {}", request.getTenantId());
        try {
            client().post()
                    .uri("/api/v1/provisioning/tenants/{tenantId}/ai", request.getTenantId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("PBX-Core AI config failed for {}: {}",
                    request.getTenantId(), e.getResponseBodyAsString());
            throw new PbxCoreException("AI configuration failed: " + e.getMessage(), e);
        }
    }

    /**
     * Remove AI config from PBX-Core Redis.
     */
    public void removeAiConfig(String tenantId) {
        log.info("PBX-Core: removing AI config for tenant {}", tenantId);
        try {
            client().delete()
                    .uri("/api/v1/provisioning/tenants/{tenantId}/ai", tenantId)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("PBX-Core AI config removal failed for {}: {}", tenantId, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Full Tenant Lifecycle
    // ════════════════════════════════════════════════════════════════

    /**
     * Remove ALL telecom config for a subscription — Kamailio, dialplan,
     * TURN, AI config, channel limits. One call cleans everything.
     */
    public void deprovisionAll(UUID subscriptionId) {
        log.info("PBX-Core: full deprovision for subscription {}", subscriptionId);
        try {
            client().delete()
                    .uri("/api/v1/provisioning/subscriptions/{subscriptionId}", subscriptionId)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(PROVISION_TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("PBX-Core full deprovision failed for {}: {}",
                    subscriptionId, e.getResponseBodyAsString());
            throw new PbxCoreException("Full deprovisioning failed: " + e.getMessage(), e);
        }
    }

    /**
     * Suspend tenant — PBX-Core marks inactive in Kamailio DB + kamcmd reload.
     * Existing calls continue, new calls rejected.
     */
    public void suspendTenant(UUID tenantId) {
        log.info("PBX-Core: suspending tenant {}", tenantId);
        try {
            client().put()
                    .uri("/api/v1/provisioning/tenants/{tenantId}/suspend", tenantId)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("PBX-Core suspend failed for {}: {}", tenantId, e.getResponseBodyAsString());
            throw new PbxCoreException("Tenant suspension failed: " + e.getMessage(), e);
        }
    }

    /**
     * Resume tenant — PBX-Core reactivates in Kamailio DB + kamcmd reload.
     */
    public void resumeTenant(UUID tenantId) {
        log.info("PBX-Core: resuming tenant {}", tenantId);
        try {
            client().put()
                    .uri("/api/v1/provisioning/tenants/{tenantId}/resume", tenantId)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            log.error("PBX-Core resume failed for {}: {}", tenantId, e.getResponseBodyAsString());
            throw new PbxCoreException("Tenant resume failed: " + e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Health
    // ════════════════════════════════════════════════════════════════

    /**
     * Health check — verify PBX-Core is reachable before provisioning.
     */
    public boolean isHealthy() {
        try {
            String status = client().get()
                    .uri("/actuator/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            return status != null && status.contains("UP");
        } catch (Exception e) {
            log.warn("PBX-Core health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Exception
    // ════════════════════════════════════════════════════════════════

    public static class PbxCoreException extends RuntimeException {
        public PbxCoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}