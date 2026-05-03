package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.AppType;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.domain.event.*;
import com.dalai.llama.tenant.domain.exception.ProvisioningException;
import com.dalai.llama.tenant.kafka.producer.TenantEventProducer;
import com.dalai.llama.tenant.repository.ProvisioningTaskRepository;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.service.KeycloakRealmService;
import com.dalai.llama.tenant.service.TenantAppService;
import com.dalai.llama.tenant.service.ProvisioningOrchestrator;
import com.dalai.llama.tenant.service.client.PbxCoreClient;
import com.dalai.llama.tenant.service.client.ProductServiceClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantAppServiceImpl implements TenantAppService {

    private final TenantAppRepository tenantAppRepository;
    private final ProvisioningOrchestrator provisioningOrchestrator;
    private final TenantEventProducer tenantEventProducer;
    private final ProductServiceClient productServiceClient;
    private final PbxCoreClient pbxCoreClient;
    private final KeycloakRealmService keycloakRealmService;
    private final KeycloakClientConfigService keycloakClientConfigService;
    private final ProvisioningTaskRepository provisioningTaskRepository;
    private final TenantRepository tenantRepository;

    @Override
    public Optional<TenantApp> getByDid(String did) {
        return tenantAppRepository.findByDidNumber(did);
    }

    @Override
    public Optional<TenantApp> getByTenantId(UUID tenantId) {
        return tenantAppRepository.findFirstByTenantId(tenantId);
    }

    @Override
    public void provisionApp(UUID tenantAppId) {
        TenantApp app = tenantAppRepository.findById(tenantAppId)
                .orElseThrow(() -> new ProvisioningException("TenantApp not found: " + tenantAppId));

        Set<ProvisioningTaskStatus> allowed = Set.of(
                ProvisioningTaskStatus.PENDING, ProvisioningTaskStatus.FAILED);
        if (!allowed.contains(app.getDeploymentStatus())) {
            throw new ProvisioningException(
                    "Cannot provision app in state " + app.getDeploymentStatus()
                            + ". Allowed: " + allowed);
        }

        log.info("Initiating provisioning for TenantApp={} tenant={}",
                tenantAppId, app.getTenant().getId());
        provisioningOrchestrator.provision(tenantAppId);
    }

    // =========================
    // PLAN ASSIGNED
    // =========================
    @Override
    public void handlePlanAssigned(PlanAssignedEvent event) {
        log.info("Plan assigned tenant={} planCode={}",
                event.getTenantId(), event.getPlanCode());

        tenantAppRepository.findFirstByTenantId(event.getTenantId())
                .ifPresent(app -> {
                    app.setPlanId(event.getPlanId());
                    app.setPlanCode(event.getPlanCode());
                    tenantAppRepository.save(app);
                    log.info("Updated plan for TenantApp {}", app.getId());
                });
    }

    // =========================
    // DID PURCHASED
    // =========================
    @Override
    public void handleDidPurchased(DidPurchasedEvent event) {
        log.info("DID purchased tenant={} number={}",
                event.getTenantId(), event.getNumber());

        tenantAppRepository.findFirstByTenantId(event.getTenantId())
                .ifPresent(app -> {
                    app.setDidId(event.getDidId());
                    app.setDidNumber(event.getNumber());
                    app.setDidCountry(event.getCountry());
                    tenantAppRepository.save(app);
                    log.info("Updated DID on TenantApp {}", app.getId());
                });
    }

    // =========================
    // SUBSCRIPTION ACTIVATED
    // =========================
    @Override
    public void handleSubscriptionActivated(SubscriptionActivatedEvent event, Tenant tenantData) {
        log.info("Processing subscription activated: tenantId={} subscriptionId={}",
                event.getTenantId(), event.getSubscriptionId());

        Optional<TenantApp> existingOpt = tenantAppRepository.findBySubscriptionId(event.getSubscriptionId());
        if (existingOpt.isPresent()) {
            TenantApp existing = existingOpt.get();
            if (existing.getDeploymentStatus() == ProvisioningTaskStatus.COMPLETED) {
                log.warn("TenantApp already COMPLETED for subscription {}, ignoring",
                        event.getSubscriptionId());
                return;
            }
            log.warn("TenantApp exists with status {} for subscription {}, ignoring",
                    existing.getDeploymentStatus(), event.getSubscriptionId());
            return;
        }

        TenantApp app = buildTenantApp(event, tenantData);
        tenantAppRepository.save(app);
        log.info("Created TenantApp {} with PENDING status for tenant {}",
                app.getId(), event.getTenantId());

        provisionApp(app.getId());

        tenantEventProducer.publishProvisioningCompleted(ProvisioningCompletedEvent.builder()
                .tenantId(app.getTenant().getId())
                .tenantAppId(app.getId())
                .subscriptionId(app.getSubscriptionId())
                .productCode(app.getProductCode())
                .status(ProvisioningTaskStatus.COMPLETED)
                .completedAt(Instant.now())
                .build());
    }

    // =========================
    // SUBSCRIPTION FAILED
    // =========================
    @Override
    public void handleSubscriptionActivationFailed(SubscriptionActivationFailedEvent event) {
        log.info("Subscription FAILED tenant={} subId={} reason={}",
                event.getTenantId(), event.getSubscriptionId(), event.getReason());

        tenantAppRepository.findBySubscriptionId(event.getSubscriptionId())
                .ifPresent(app -> {
                    app.setDeploymentStatus(ProvisioningTaskStatus.FAILED);
                    tenantAppRepository.save(app);
                    log.info("Marked TenantApp {} as FAILED", app.getId());
                });
    }

    // =========================
    // DELETE APP
    // =========================
    @Override
    @Transactional
    public void deleteApp(UUID tenantAppId, UUID tenantId) {
        TenantApp app = tenantAppRepository.findById(tenantAppId)
                .orElseThrow(() -> new ProvisioningException("TenantApp not found: " + tenantAppId));

        Tenant tenant = app.getTenant();
        if (!tenant.getId().equals(tenantId)) {
            throw new ProvisioningException("TenantApp does not belong to tenant");
        }

        // Block deletion while provisioning is actively running
        if (app.getDeploymentStatus() == ProvisioningTaskStatus.RUNNING) {
            throw new ProvisioningException(
                    "Cannot delete app while provisioning is RUNNING. Wait for it to complete or fail.");
        }

        log.info("Deleting TenantApp={} subscription={} tenant={} status={}",
                tenantAppId, app.getSubscriptionId(), tenantId, app.getDeploymentStatus());

        // 1. Deprovision PBX-Core (Kamailio, FreeSWITCH, TURN, AI config, Redis)
        if (app.getSubscriptionId() != null) {
            try {
                pbxCoreClient.deprovisionAll(app.getSubscriptionId());
                log.info("PBX-Core deprovision completed for subscription {}", app.getSubscriptionId());
            } catch (Exception e) {
                log.warn("PBX-Core deprovision failed (continuing cleanup): {}", e.getMessage());
            }
        }

        // 2. Cleanup product-service (DID, SIP endpoint, channels, trunk, subscription)
        if (app.getSubscriptionId() != null) {
            try {
                productServiceClient.cleanupSubscription(app.getSubscriptionId());
                log.info("Product-service cleanup completed for subscription {}", app.getSubscriptionId());
            } catch (Exception e) {
                log.warn("Product-service cleanup failed (continuing): {}", e.getMessage());
            }
        }

        // 3. Keycloak — delete OIDC client specific to this app
        if (app.getKeycloakClientId() != null && tenant.getKeycloakRealmName() != null) {
            try {
                keycloakClientConfigService.deleteClient(
                        tenant.getKeycloakRealmName(), app.getKeycloakClientId());
                log.info("Keycloak client {} deleted from realm {}",
                        app.getKeycloakClientId(), tenant.getKeycloakRealmName());
            } catch (Exception e) {
                log.warn("Keycloak client deletion failed (continuing): {}", e.getMessage());
            }
        }

        // 4. If this is the LAST app for the tenant, also clean admin user
        long remainingApps = tenantAppRepository.countByTenantId(tenantId) - 1; // -1 for the one being deleted
        if (remainingApps <= 0 && tenant.getAdminUserId() != null) {
            // Delete admin user from tenant realm
            if (tenant.getKeycloakRealmName() != null) {
                try {
                    keycloakRealmService.deleteAdminUser(
                            tenant.getKeycloakRealmName(), tenant.getAdminUserId());
                    log.info("Keycloak admin user {} deleted (last app)", tenant.getAdminUserId());
                } catch (Exception e) {
                    log.warn("Keycloak admin user deletion failed (continuing): {}", e.getMessage());
                }
            }

            // Unlink from platform realm
            try {
                keycloakRealmService.removeTenantLinkFromMainUser(
                        tenant.getAdminUserId(), "dalai-llama");
                log.info("Admin user {} unlinked from platform realm", tenant.getAdminUserId());
            } catch (Exception e) {
                log.warn("Platform realm unlink failed (continuing): {}", e.getMessage());
            }

            // Clear admin references on Tenant
            tenant.setAdminUserId(null);
            tenant.setAdminUserEmail(null);
            tenantRepository.save(tenant);
        }

        // 7. Delete provisioning tasks for this app
        try {
            provisioningTaskRepository.deleteAllByTenantAppId(tenantAppId);
            log.info("Provisioning tasks deleted for app {}", tenantAppId);
        } catch (Exception e) {
            log.warn("Provisioning task cleanup failed (continuing): {}", e.getMessage());
        }

        // 8. Delete TenantApp record
        tenantAppRepository.delete(app);
        log.info("TenantApp {} deleted for tenant {}", tenantAppId, tenantId);
    }

    // =========================
    // RETRY PROVISION
    // =========================
    @Override
    @Transactional
    public void retryProvision(UUID tenantAppId, UUID tenantId) {
        TenantApp app = tenantAppRepository.findById(tenantAppId)
                .orElseThrow(() -> new ProvisioningException("TenantApp not found: " + tenantAppId));

        if (!app.getTenant().getId().equals(tenantId)) {
            throw new ProvisioningException("TenantApp does not belong to tenant");
        }

        // Allow retry from PENDING, FAILED, or RUNNING (stuck/crashed mid-provision)
        if (app.getDeploymentStatus() == ProvisioningTaskStatus.COMPLETED) {
            throw new ProvisioningException(
                    "Cannot retry provisioning — app is already COMPLETED");
        }

        // Reset retry count on the existing task so manual retry always works
        // (even if auto-retries from the scheduler are exhausted)
        provisioningTaskRepository
                .findFirstByTenantAppIdAndStatusInOrderByStartedAtDesc(
                        tenantAppId,
                        List.of(ProvisioningTaskStatus.PENDING, ProvisioningTaskStatus.FAILED, ProvisioningTaskStatus.RUNNING))
                .ifPresent(task -> {
                    log.info("Resetting retry count for task={} currentStep={} retries={}",
                            task.getId(), task.getCurrentStep(), task.getRetryCount());
                    task.setRetryCount(0);
                    provisioningTaskRepository.save(task);
                });

        log.info("Retrying provisioning for TenantApp={} tenant={} currentStatus={}",
                tenantAppId, tenantId, app.getDeploymentStatus());
        provisioningOrchestrator.provision(tenantAppId);
    }

    // ==================== PRIVATE HELPERS ====================

    private TenantApp buildTenantApp(SubscriptionActivatedEvent event, Tenant tenant) {
        TenantApp app = TenantApp.builder()
                // Core
                .subscriptionId(event.getSubscriptionId())
                .tenant(tenant)
                .appType(resolveAppType(event.getProductCode()))
                .subdomain(resolveSubdomain(event.getProductCode(), tenant.getSlug()))
                .displayName(event.getProductName() != null ? event.getProductName() : event.getProductCode())
                // Product & Plan
                .productCode(event.getProductCode())
                .planId(event.getPlanId())
                .planCode(event.getPlanCode())
                .planTier(event.getPlanTier())
                // Entitlements
                .agentSeats(event.getAgentSeats())
                .maxAgents(event.getMaxAgents())
                .maxDids(event.getMaxDids())
                .maxChannels(event.getMaxChannels())
                .includedMinutes(event.getIncludedMinutes())
                .aiRatePerMinute(event.getAiRatePerMin())
                // DID
                .didId(event.getDidId())
                .didNumber(event.getDidNumber())
                .didDisplayNumber(event.getDidDisplayNumber())
                .didCountry(event.getDidCountry())
                .didRegion(event.getDidRegion())
                .didCity(event.getDidCity())
                // SIP Endpoint
                .sipEndpointId(event.getSipEndpointId())
                .sipEndpointUsername(event.getSipEndpointUsername())
                .sipEndpointPasswordHash(event.getSipEndpointPasswordHash())
                .sipEndpointDomain(event.getSipEndpointDomain())
                .sipEndpointRealm(event.getSipEndpointRealm())
                // Channels
                .channelBundleId(event.getChannelBundleId())
                .channelDirection(event.getChannelDirection())
                .channelTotal(event.getTotalChannels())
                .channelInbound(event.getInboundChannels())
                .channelOutbound(event.getOutboundChannels())
                // Tenant Trunk
                .tenantTrunkId(event.getTenantSipTrunkId())
                .tenantTrunkUsername(event.getTenantSipTrunkUsername())
                .tenantTrunkPasswordHash(event.getTenantSipTrunkPasswordHash())
                .tenantTrunkDomain(event.getTenantSipTrunkDomain())
                .tenantTrunkPort(event.getTenantSipTrunkPort())
                .tenantTrunkRealm(event.getTenantSipTrunkRealm())
                .tenantTrunkTransport(event.getTenantSipTrunkTransport())
                .tenantTrunkMaxCalls(event.getTenantSipTrunkMaxConcurrentCalls())
                // Platform Trunk
                .platformTrunkId(event.getPlatformTrunkId())
                .platformTrunkProvider(event.getPlatformTrunkProvider())
                .platformTrunkServer(event.getPlatformTrunkServer())
                .platformTrunkPort(event.getPlatformTrunkPort())
                .platformTrunkTransport(event.getPlatformTrunkTransport())
                .platformTrunkCodecs(event.getPlatformTrunkCodecs())
                // Status
                .deploymentStatus(ProvisioningTaskStatus.PENDING)
                .build();

        if (event.getProductApps() != null && !event.getProductApps().isEmpty()) {
            app.setAppPanels(buildAppPanelsWithUrls(event.getProductApps(), tenant.getSlug()));
        }

        return app;
    }

    private String buildAppPanelsWithUrls(List<SubscriptionActivatedEvent.ProductAppData> apps, String tenantSlug) {
        try {
            List<Map<String, Object>> panels = apps.stream().map(app -> {
                Map<String, Object> panel = new LinkedHashMap<>();
                panel.put("appType", app.getAppType());
                panel.put("displayName", app.getDisplayName());
                panel.put("subdomain", app.getSubdomain());
                panel.put("icon", app.getIcon());
                panel.put("displayOrder", app.getDisplayOrder());
                panel.put("url", "https://" + app.getSubdomain() + "-" + tenantSlug + ".dalaillama.in");
                return panel;
            }).toList();
            return new ObjectMapper().writeValueAsString(panels);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize app panels", e);
        }
    }

    private AppType resolveAppType(String productCode) {
        return switch (productCode) {
            case "AI_CC" -> AppType.CONTACT_CENTER;
            case "CONV_IVR" -> AppType.CONV_IVR;
            case "BASIC_PBX" -> AppType.BASIC_PBX;
            case "OUTBOUND_DIALER" -> AppType.OUTBOUND_DIALER;
            case "VIRTUAL_RECEPTIONIST" -> AppType.VIRTUAL_RECEPTIONIST;
            default -> AppType.CONTACT_CENTER;
        };
    }

    private String resolveSubdomain(String productCode, String tenantSlug) {
        String prefix = switch (productCode) {
            case "AI_CC" -> "cc";
            case "CONV_IVR" -> "ivr";
            case "BASIC_PBX" -> "pbx";
            case "OUTBOUND_DIALER" -> "dialer";
            case "VIRTUAL_RECEPTIONIST" -> "vr";
            default -> productCode.toLowerCase().replace("_", "-");
        };
        return prefix + "-" + tenantSlug;
    }
}