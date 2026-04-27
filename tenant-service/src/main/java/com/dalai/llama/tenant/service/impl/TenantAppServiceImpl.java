package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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

        // Guard: only provision if PENDING or FAILED (retry)
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

    @Override
    public void handleSubscriptionActivated(SubscriptionActivatedEvent event) {
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
        TenantApp app = buildTenantApp(event);
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

    private TenantApp buildTenantApp(SubscriptionActivatedEvent event) {
        TenantApp app = TenantApp.builder()
                .subscriptionId(event.getSubscriptionId())
                .tenant(Tenant.builder().id(event.getTenantId()).build())
                .productCode(event.getProductCode())
                .planId(event.getPlanId())
                .planCode(event.getPlanCode())
                .planTier(event.getPlanTier())
                .agentSeats(event.getAgentSeats())
                .maxAgents(event.getMaxAgents())
                .maxDids(event.getMaxDids())
                .maxChannels(event.getMaxChannels())
                .includedMinutes(event.getIncludedMinutes())
                .aiRatePerMinute(event.getAiRatePerMin())
                .didId(event.getDidId())
                .didNumber(event.getDidNumber())
                .didDisplayNumber(event.getDidDisplayNumber())
                .didCountry(event.getDidCountry())
                .didRegion(event.getDidRegion())
                .didCity(event.getDidCity())
                .sipEndpointId(event.getSipEndpointId())
                .sipEndpointUsername(event.getSipEndpointUsername())
                .sipEndpointPasswordHash(event.getSipEndpointPasswordHash())
                .sipEndpointDomain(event.getSipEndpointDomain())
                .sipEndpointRealm(event.getSipEndpointRealm())
                .channelBundleId(event.getChannelBundleId())
                .channelDirection(event.getChannelDirection())
                .channelTotal(event.getTotalChannels())
                .channelInbound(event.getInboundChannels())
                .channelOutbound(event.getOutboundChannels())
                .tenantTrunkId(event.getTenantSipTrunkId())
                .tenantTrunkUsername(event.getTenantSipTrunkUsername())
                .tenantTrunkPasswordHash(event.getTenantSipTrunkPasswordHash())
                .tenantTrunkDomain(event.getTenantSipTrunkDomain())
                .tenantTrunkPort(event.getTenantSipTrunkPort())
                .tenantTrunkRealm(event.getTenantSipTrunkRealm())
                .tenantTrunkTransport(event.getTenantSipTrunkTransport())
                .tenantTrunkMaxCalls(event.getTenantSipTrunkMaxConcurrentCalls())
                .platformTrunkId(event.getPlatformTrunkId())
                .platformTrunkProvider(event.getPlatformTrunkProvider())
                .platformTrunkServer(event.getPlatformTrunkServer())
                .platformTrunkPort(event.getPlatformTrunkPort())
                .platformTrunkTransport(event.getPlatformTrunkTransport())
                .platformTrunkCodecs(event.getPlatformTrunkCodecs())
                .deploymentStatus(ProvisioningTaskStatus.PENDING)
                .build();

        if (event.getProductApps() != null && !event.getProductApps().isEmpty()) {
            app.setAppPanels(convertAppsToJson(event.getProductApps()));
        }


        return app;
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

                    app.setDeploymentStatus(
                            com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus.FAILED
                    );

                    tenantAppRepository.save(app);

                    // ✅ Push failure to UI

                    log.info("Marked TenantApp {} as FAILED", app.getId());
                });
    }

    private String convertAppsToJson(List<SubscriptionActivatedEvent.ProductAppData> apps) {
        try {
            return new ObjectMapper().writeValueAsString(apps);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize app panels", e);
        }
    }
}