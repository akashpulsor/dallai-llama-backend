package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.AppType;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.domain.event.*;
import com.dalai.llama.tenant.domain.exception.ProvisioningException;
import com.dalai.llama.tenant.kafka.producer.TenantEventProducer;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.dalai.llama.tenant.service.TenantAppService;
import com.dalai.llama.tenant.service.ProvisioningOrchestrator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantAppServiceImpl implements TenantAppService {

    private final TenantAppRepository tenantAppRepository;
    private final ProvisioningOrchestrator provisioningOrchestrator;
    private final TenantEventProducer tenantEventProducer;

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