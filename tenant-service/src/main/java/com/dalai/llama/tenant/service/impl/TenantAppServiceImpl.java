package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.domain.event.DidPurchasedEvent;
import com.dalai.llama.tenant.domain.event.PlanAssignedEvent;
import com.dalai.llama.tenant.domain.event.SubscriptionActivatedEvent;
import com.dalai.llama.tenant.domain.event.SubscriptionActivationFailedEvent;
import com.dalai.llama.tenant.domain.exception.ProvisioningException;
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

    // =========================
    // SUBSCRIPTION ACTIVATED
    // =========================
    @Override
    public void handleSubscriptionActivated(SubscriptionActivatedEvent event) {

        log.info("Subscription activated tenant={} subId={}",
                event.getTenantId(), event.getSubscriptionId());

        Optional<TenantApp> existingOpt =
                tenantAppRepository.findBySubscriptionId(event.getSubscriptionId());

        if (existingOpt.isPresent()) {

            TenantApp existing = existingOpt.get();

            // ✅ Already active → ignore
            if (existing.getDeploymentStatus() == ProvisioningTaskStatus.COMPLETED) {
                log.warn("TenantApp already ACTIVE for subscription {}, skipping",
                        event.getSubscriptionId());
                return;
            }

            // ⚠️ Retry case (FAILED / PENDING)
            log.warn("Retrying provisioning for subscription {} with status {}",
                    event.getSubscriptionId(), existing.getDeploymentStatus());

            // Optional: reset state before retry
            existing.setDeploymentStatus(ProvisioningTaskStatus.PENDING);
            tenantAppRepository.save(existing);

            provisionApp(existing.getId());
            return;
        }

        // ==================== CREATE NEW ====================
        TenantApp app = TenantApp.builder()

                // CORE
                .subscriptionId(event.getSubscriptionId())
                .tenant(Tenant.builder().id(event.getTenantId()).build())

                // PRODUCT
                .productCode(event.getProductCode())
                .planId(event.getPlanId())
                .planCode(event.getPlanCode())
                .planTier(event.getPlanTier())

                // ENTITLEMENTS
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

                // SIP ENDPOINT
                .sipEndpointId(event.getSipEndpointId())
                .sipEndpointUsername(event.getSipEndpointUsername())
                .sipEndpointPasswordHash(event.getSipEndpointPasswordHash())
                .sipEndpointDomain(event.getSipEndpointDomain())
                .sipEndpointRealm(event.getSipEndpointRealm())

                // CHANNELS
                .channelBundleId(event.getChannelBundleId())
                .channelDirection(event.getChannelDirection())
                .channelTotal(event.getTotalChannels())
                .channelInbound(event.getInboundChannels())
                .channelOutbound(event.getOutboundChannels())

                // TENANT TRUNK
                .tenantTrunkId(event.getTenantSipTrunkId())
                .tenantTrunkUsername(event.getTenantSipTrunkUsername())
                .tenantTrunkPasswordHash(event.getTenantSipTrunkPasswordHash())
                .tenantTrunkDomain(event.getTenantSipTrunkDomain())
                .tenantTrunkPort(event.getTenantSipTrunkPort())
                .tenantTrunkRealm(event.getTenantSipTrunkRealm())
                .tenantTrunkTransport(event.getTenantSipTrunkTransport())
                .tenantTrunkMaxCalls(event.getTenantSipTrunkMaxConcurrentCalls())

                // PLATFORM TRUNK
                .platformTrunkId(event.getPlatformTrunkId())
                .platformTrunkProvider(event.getPlatformTrunkProvider())
                .platformTrunkServer(event.getPlatformTrunkServer())
                .platformTrunkPort(event.getPlatformTrunkPort())
                .platformTrunkTransport(event.getPlatformTrunkTransport())
                .platformTrunkCodecs(event.getPlatformTrunkCodecs())

                // ✅ IMPORTANT: ALWAYS START AS PENDING
                .deploymentStatus(ProvisioningTaskStatus.PENDING)

                .build();

        tenantAppRepository.save(app);

        log.info("TenantApp created with PENDING state {}", app.getId());

        // UI Panels
        if (event.getProductApps() != null && !event.getProductApps().isEmpty()) {
            app.setAppPanels(convertAppsToJson(event.getProductApps()));
            tenantAppRepository.save(app);
        }

        // Trigger provisioning
        provisionApp(app.getId());
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