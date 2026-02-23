package com.dalai.llama.tenant.service.impl;


import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.dto.response.PlanEntitlementResponse;
import com.dalai.llama.tenant.dto.response.ProductConfigResponse;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.dalai.llama.tenant.service.client.ProductServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Telecom Provisioning Orchestrator
 *
 * Master orchestrator that coordinates all provisioning services:
 *
 * 1. Infrastructure (K8s URLs, dedicated namespace)
 * 2. Kamailio (SIP proxy, subscriber, dialplan)
 * 3. FreePBX/FreeSWITCH (PBX, dialplan, queues)
 * 4. RTPEngine (media relay, recording)
 * 5. CoTURN (TURN/STUN)
 * 6. AI Service (STT/TTS/Bot integration)
 * 7. Istio Gateway (external access)
 * 8. Keycloak (OIDC clients)
 *
 * Flow:
 * product-service → tenant-service → TelecomProvisioningOrchestrator
 *                                              ↓
 *                    ┌─────────────────────────┼─────────────────────────┐
 *                    ↓                         ↓                         ↓
 *              Kamailio              FreePBX/FreeSWITCH              RTPEngine
 *                    ↓                         ↓                         ↓
 *                CoTURN                   AI Service                  Istio
 *                    ↓                         ↓                         ↓
 *                         ← ← ← Keycloak Clients → → →
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelecomProvisioningOrchestrator {

    private final KubernetesConfigDiscoveryService configDiscovery;
    private final KamailioConfigService kamailioConfigService;
    private final FreePBXConfigService freePBXConfigService;
    private final RTPEngineConfigService rtpEngineConfigService;
    private final CoTurnConfigService coTurnConfigService;
    private final AiServiceConfigService aiServiceConfigService;
    private final DedicatedNamespaceProvisioner dedicatedProvisioner;
    private final IstioGatewayConfigService istioGatewayService;
    private final KeycloakClientConfigService keycloakClientService;
    private final TenantAppRepository tenantAppRepository;

    /**
     * Main entry point - provision complete telecom stack
     */
    @Transactional
    public void provisionTelecomStack(TenantApp app, ProductConfigResponse config) {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║ TELECOM PROVISIONING START                                   ║");
        log.info("║ Tenant: {} | Product: {} | Plan: {}",
                app.getNamespace(), app.getProductCode(), app.getPlanCode());
        log.info("╚══════════════════════════════════════════════════════════════╝");

        PlanEntitlementResponse entitlements = config.entitlements();
        boolean isDedicated = Boolean.TRUE.equals(app.getDedicatedInfrastructure());

        try {
            // Phase 1: Infrastructure
            log.info("▶ Phase 1: Infrastructure Configuration");
            configureInfrastructure(app, isDedicated);
            app.setDeploymentStatus(ProvisioningTaskStatus.RUNNING);
            tenantAppRepository.save(app);

            // Phase 2: Kamailio (SIP Proxy)
            log.info("▶ Phase 2: Kamailio Configuration");
            String kamailioConfig = kamailioConfigService.configureForSubscription(app, entitlements);
            app.setKamailioConfig(kamailioConfig);
            app.setKamailioSynced(true);
            app.setKamailioSyncedAt(Instant.now());

            // Phase 3: FreePBX/FreeSWITCH (PBX)
            log.info("▶ Phase 3: FreePBX Configuration");
            String freepbxConfig = freePBXConfigService.configureForSubscription(app, entitlements);
            app.setCcBuilderConfig(freepbxConfig);
            app.setFreepbxSynced(true);
            app.setFreepbxSyncedAt(Instant.now());

            // Phase 4: RTPEngine (Media)
            log.info("▶ Phase 4: RTPEngine Configuration");
            rtpEngineConfigService.configureForSubscription(app, entitlements);

            // Phase 5: CoTURN (TURN/STUN)
            log.info("▶ Phase 5: CoTURN Configuration");
            coTurnConfigService.configureForSubscription(app);

            // Phase 6: AI Service (if enabled)
            if (entitlements.aiBotEnabled() || entitlements.aiSttEnabled()) {
                log.info("▶ Phase 6: AI Service Configuration");
                aiServiceConfigService.configureForSubscription(app, entitlements);
            }

            // Phase 7: Dedicated Namespace (if ENTERPRISE)
            if (isDedicated) {
                log.info("▶ Phase 7: Dedicated Namespace Provisioning");
                dedicatedProvisioner.provisionDedicatedNamespace(app,
                        () -> onProvisioningComplete(app),
                        () -> onProvisioningError(app));
                return; // Async - will complete later
            }

            // Phase 8: Istio Gateway
            log.info("▶ Phase 8: Istio Gateway Configuration");
            istioGatewayService.createVirtualServiceForTenant(app);

            // Phase 9: Keycloak Clients
            log.info("▶ Phase 9: Keycloak Client Configuration");
            keycloakClientService.createClientsForTenant(app);
            keycloakClientService.createDefaultRoles(app.getNamespace());

            // Complete
            onProvisioningComplete(app);

        } catch (Exception e) {
            log.error("Telecom provisioning failed: {}", e.getMessage(), e);
            onProvisioningError(app);
            throw new RuntimeException("Telecom provisioning failed", e);
        }
    }

    private void configureInfrastructure(TenantApp app, boolean isDedicated) {
        String slug = app.getNamespace();
        String baseDomain = configDiscovery.getBaseDomain();

        if (isDedicated) {
            var endpoints = configDiscovery.buildDedicatedEndpoints(slug);
            app.setPostgresUrl(endpoints.postgresUrl());
            app.setRedisUrl(endpoints.redisUrl());
            app.setKafkaBootstrap(endpoints.kafkaBootstrap());
            app.setSipExternalIp(endpoints.sipExternalIp());
            app.setSipUdpUrl("sip:" + endpoints.sipExternalIp() + ":5060");
            app.setSipTlsUrl("sips:" + endpoints.sipExternalIp() + ":5061");
            app.setTurnUrl("turn:" + endpoints.turnHost() + ":3478");
            app.setWebsocketUrl(endpoints.websocketUrl());
            app.setRtpengineSock(endpoints.rtpengineSocket());
            app.setFreeswitchEslHost(endpoints.freeswitchHost());
            app.setFreeswitchEslPort(endpoints.freeswitchEslPort());
            app.setFreeswitchEslPassword(endpoints.freeswitchEslPassword());
        } else {
            app.setPostgresUrl(configDiscovery.getSharedPostgresUrl() + "?currentSchema=" + slug);
            app.setRedisUrl(configDiscovery.getSharedRedisUrl());
            app.setKafkaBootstrap(configDiscovery.getSharedKafkaBootstrap());
            app.setSipExternalIp(configDiscovery.getSharedSipHost());
            app.setSipUdpUrl("sip:" + configDiscovery.getSharedSipHost() + ":5060");
            app.setSipTlsUrl("sips:" + configDiscovery.getSharedSipHost() + ":5061");
            app.setTurnUrl("turn:" + configDiscovery.getSharedTurnHost() + ":3478");
            app.setWebsocketUrl("wss://ws." + baseDomain + "/ws");
            app.setRtpengineSock(configDiscovery.getSharedRtpengineSocket());
            app.setFreeswitchEslHost(configDiscovery.getSharedFreeswitchHost());
            app.setFreeswitchEslPort(configDiscovery.getSharedFreeswitchEslPort());
            app.setFreeswitchEslPassword(configDiscovery.getSharedFreeswitchEslPassword());
        }

        app.setDashboardUrl("https://app." + slug + "." + baseDomain);
    }

    private void onProvisioningComplete(TenantApp app) {
        app.setDeploymentStatus(ProvisioningTaskStatus.COMPLETED);
        app.setDeployedAt(Instant.now());
        tenantAppRepository.save(app);

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║ TELECOM PROVISIONING COMPLETE                                ║");
        log.info("║ Tenant: {} | Dashboard: {}", app.getNamespace(), app.getDashboardUrl());
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }

    private void onProvisioningError(TenantApp app) {
        app.setDeploymentStatus(ProvisioningTaskStatus.FAILED);
        tenantAppRepository.save(app);

        log.error("╔══════════════════════════════════════════════════════════════╗");
        log.error("║ TELECOM PROVISIONING FAILED                                  ║");
        log.error("║ Tenant: {}", app.getNamespace());
        log.error("╚══════════════════════════════════════════════════════════════╝");
    }

    /**
     * Deprovision telecom stack (on subscription cancel)
     */
    @Transactional
    public void deprovisionTelecomStack(UUID subscriptionId) {
        TenantApp app = tenantAppRepository.findBySubscriptionId(subscriptionId)
                .orElseThrow(() -> new RuntimeException("TenantApp not found: " + subscriptionId));

        log.info("Deprovisioning telecom stack for subscription {}", subscriptionId);

        boolean isDedicated = Boolean.TRUE.equals(app.getDedicatedInfrastructure());

        // Remove Kamailio config
        kamailioConfigService.removeSubscriptionConfig(subscriptionId);

        // Remove RTPEngine config
        rtpEngineConfigService.removeSubscriptionConfig(app.getTenant().getId().toString());

        // Remove CoTURN config
        coTurnConfigService.removeSubscriptionConfig(app.getNamespace());

        // Remove AI config
        aiServiceConfigService.removeSubscriptionConfig(app.getTenant().getId().toString());

        // Remove Istio VirtualServices
        istioGatewayService.deleteVirtualServicesForTenant(app.getNamespace(),
                isDedicated ? app.getNamespace() : "dalaillama");

        // Remove Keycloak clients
        keycloakClientService.deleteClientsForTenant(app.getNamespace());

        // Delete dedicated namespace if exists
        if (isDedicated) {
            dedicatedProvisioner.deleteDedicatedNamespace(app.getNamespace());
        }

        // Update status
        app.setDeploymentStatus(ProvisioningTaskStatus.CANCELLED);
        app.setEnabled(false);
        tenantAppRepository.save(app);

        log.info("Telecom stack deprovisioned for subscription {}", subscriptionId);
    }

    /**
     * Refresh telecom config (on plan change)
     */
    @Transactional
    public void refreshTelecomConfig(TenantApp app, ProductConfigResponse newConfig) {
        log.info("Refreshing telecom config for {} with new plan {}",
                app.getNamespace(), newConfig.plan().code());

        PlanEntitlementResponse entitlements = newConfig.entitlements();

        // Update Kamailio (channel limits, etc.)
        String kamailioConfig = kamailioConfigService.configureForSubscription(app, entitlements);
        app.setKamailioConfig(kamailioConfig);
        app.setKamailioSynced(true);
        app.setKamailioSyncedAt(Instant.now());

        // Update FreePBX (features, queues, etc.)
        String freepbxConfig = freePBXConfigService.configureForSubscription(app, entitlements);
        app.setCcBuilderConfig(freepbxConfig);
        app.setFreepbxSynced(true);
        app.setFreepbxSyncedAt(Instant.now());

        // Update RTPEngine (codecs, recording)
        rtpEngineConfigService.configureForSubscription(app, entitlements);

        // Update AI Service config
        if (entitlements.aiBotEnabled() || entitlements.aiSttEnabled()) {
            aiServiceConfigService.configureForSubscription(app, entitlements);
        }

        tenantAppRepository.save(app);
        log.info("Telecom config refreshed for {}", app.getNamespace());
    }
}