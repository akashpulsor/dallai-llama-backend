package com.dalai.llama.pbx.core.controller.provisioning;


import com.dalai.llama.pbx.core.dto.request.provisioning.*;
import com.dalai.llama.pbx.core.dto.response.TenantTelecomEndpoints;
import com.dalai.llama.pbx.core.dto.response.TurnCredentialsResponse;
import com.dalai.llama.pbx.core.service.provisioning.ProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Provisioning API — matches tenant-service PbxCoreClient URLs exactly.
 *
 * Secured by Istio mTLS (chain 2 in SecurityConfig — no JWT required).
 * tenant-service is the ONLY caller of these endpoints.
 *
 * Provisioning sequence (tenant-service calls in order):
 *   1. POST  .../kamailio           → DB writes + Redis + kamcmd reload
 *   2. POST  .../freeswitch/dialplan → DB upsert (mod_xml_curl serves this)
 *   3. POST  .../rtpengine          → Redis hash
 *   4. POST  .../turn               → Redis + DB + return creds
 *   5. POST  .../ai                 → Redis JSON
 *
 * Lifecycle:
 *   PUT     .../suspend             → is_active=false + kamcmd reload
 *   PUT     .../resume              → is_active=true + kamcmd reload
 *   DELETE  /subscriptions/{id}     → full cleanup
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/provisioning")
@RequiredArgsConstructor
public class ProvisioningController {

    private final ProvisioningService provisioningService;

    // ═══════════════════════════════════════════════════════════
    // KAMAILIO — subscriber, domain, dispatcher, dialplan, channel limits
    // ═══════════════════════════════════════════════════════════

    /**
     * Full Kamailio provisioning.
     * PbxCoreClient.provisionKamailio() calls this.
     * Returns TenantTelecomEndpoints which tenant-service stores on TenantApp.
     */
    @PostMapping("/tenants/{tenantId}/kamailio")
    public ResponseEntity<TenantTelecomEndpoints> provisionKamailio(
            @PathVariable UUID tenantId,
            @RequestBody KamailioProvisioningRequest request) {
        request.setTenantId(tenantId); // URL takes precedence
        TenantTelecomEndpoints endpoints = provisioningService.provisionKamailio(request);
        return ResponseEntity.ok(endpoints);
    }

    /**
     * Remove Kamailio config for a subscription.
     * PbxCoreClient.deprovisionKamailio() calls this.
     */
    @DeleteMapping("/subscriptions/{subscriptionId}/kamailio")
    public ResponseEntity<Void> deprovisionKamailio(@PathVariable UUID subscriptionId) {
        provisioningService.deprovisionAll(subscriptionId);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════
    // FREESWITCH DIALPLAN — pre-generated XML stored for mod_xml_curl
    // ═══════════════════════════════════════════════════════════

    /**
     * Store FreeSWITCH dialplan XML.
     * PbxCoreClient.storeFreeSwitchDialplan() calls this.
     */
    @PostMapping("/tenants/{tenantId}/freeswitch/dialplan")
    public ResponseEntity<Void> storeDialplan(
            @PathVariable UUID tenantId,
            @RequestBody FreeSwitchDialplanRequest request) {
        request.setTenantId(tenantId);
        provisioningService.storeDialplan(request);
        return ResponseEntity.ok().build();
    }

    // ═══════════════════════════════════════════════════════════
    // RTPENGINE — per-tenant codec/recording/AI-fork config
    // ═══════════════════════════════════════════════════════════

    /**
     * Store RTPEngine config in Redis.
     * PbxCoreClient.configureRtpEngine() calls this.
     */
    @PostMapping("/tenants/{tenantId}/rtpengine")
    public ResponseEntity<Void> configureRtpEngine(
            @PathVariable UUID tenantId,
            @RequestBody RtpEngineConfigRequest request) {
        request.setTenantId(tenantId);
        provisioningService.storeRtpEngineConfig(request);
        return ResponseEntity.ok().build();
    }

    /**
     * Remove RTPEngine config.
     * PbxCoreClient.removeRtpEngineConfig() calls this.
     */
    @DeleteMapping("/tenants/{tenantId}/rtpengine")
    public ResponseEntity<Void> removeRtpEngineConfig(@PathVariable UUID tenantId) {
        provisioningService.removeRtpEngineConfig(tenantId);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════
    // TURN — HMAC-SHA1 credential generation
    // ═══════════════════════════════════════════════════════════

    /**
     * Generate TURN credentials.
     * PbxCoreClient.configureTurn() calls this.
     * Returns TurnCredentialsResponse — tenant-service stores turnUrl on TenantApp.
     */
    @PostMapping("/tenants/{tenantId}/turn")
    public ResponseEntity<TurnCredentialsResponse> configureTurn(
            @PathVariable UUID tenantId,
            @RequestBody TurnConfigRequest request) {
        request.setTenantId(tenantId);
        TurnCredentialsResponse response = provisioningService.configureTurn(tenantId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Remove TURN credentials.
     * PbxCoreClient.removeTurnConfig() calls this.
     */
    @DeleteMapping("/tenants/{slug}/turn")
    public ResponseEntity<Void> removeTurnConfig(@PathVariable String slug) {
        provisioningService.removeTurnConfig(slug);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════
    // AI CONFIG — full AI mode config stored in Redis
    // ═══════════════════════════════════════════════════════════

    /**
     * Store AI config.
     * PbxCoreClient.configureAi() calls this.
     */
    @PostMapping("/tenants/{tenantId}/ai")
    public ResponseEntity<Void> configureAi(
            @PathVariable UUID tenantId,
            @RequestBody AiConfigRequest request) {
        request.setTenantId(tenantId);
        provisioningService.storeAiConfig(request);
        return ResponseEntity.ok().build();
    }

    /**
     * Remove AI config.
     * PbxCoreClient.removeAiConfig() calls this.
     */
    @DeleteMapping("/tenants/{tenantId}/ai")
    public ResponseEntity<Void> removeAiConfig(@PathVariable UUID tenantId) {
        provisioningService.removeAiConfig(tenantId);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════
    // TENANT LIFECYCLE — suspend, resume, deprovision
    // ═══════════════════════════════════════════════════════════

    /**
     * Suspend tenant.
     * PbxCoreClient.suspendTenant() calls this.
     */
    @PutMapping("/tenants/{tenantId}/suspend")
    public ResponseEntity<Void> suspendTenant(@PathVariable UUID tenantId) {
        provisioningService.suspendTenant(tenantId);
        return ResponseEntity.ok().build();
    }

    /**
     * Resume tenant.
     * PbxCoreClient.resumeTenant() calls this.
     */
    @PutMapping("/tenants/{tenantId}/resume")
    public ResponseEntity<Void> resumeTenant(@PathVariable UUID tenantId) {
        provisioningService.resumeTenant(tenantId);
        return ResponseEntity.ok().build();
    }

    /**
     * Full deprovision — remove ALL telecom config for a subscription.
     * PbxCoreClient.deprovisionAll() calls this.
     */
    @DeleteMapping("/subscriptions/{subscriptionId}")
    public ResponseEntity<Void> deprovisionAll(@PathVariable UUID subscriptionId) {
        provisioningService.deprovisionAll(subscriptionId);
        return ResponseEntity.noContent().build();
    }
}