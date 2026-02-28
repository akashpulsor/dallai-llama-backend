package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.*;
import com.dalai.llama.tenant.domain.entity.enums.AppType;
import com.dalai.llama.tenant.domain.entity.enums.DeploymentModel;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import com.dalai.llama.tenant.domain.event.TenantCreatedEvent;
import com.dalai.llama.tenant.domain.event.TenantDeletedEvent;
import com.dalai.llama.tenant.domain.exception.TenantAlreadyExistsException;
import com.dalai.llama.tenant.domain.exception.TenantNotFoundException;
import com.dalai.llama.tenant.dto.mapper.TenantMapper;
import com.dalai.llama.tenant.dto.request.CreateTenantRequest;
import com.dalai.llama.tenant.dto.request.SubscriptionActiveRequest;
import com.dalai.llama.tenant.dto.request.UpdateTenantRequest;
import com.dalai.llama.tenant.dto.response.*;
import com.dalai.llama.tenant.kafka.producer.TenantEventProducer;
import com.dalai.llama.tenant.repository.*;
import com.dalai.llama.tenant.service.*;
import com.dalai.llama.tenant.service.client.BillingServiceClient;
import com.dalai.llama.tenant.service.client.ProductServiceClient;

import com.dalai.llama.tenant.util.SlugGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TenantServiceImpl implements TenantService {

    // ================================================================
    // DEPENDENCIES
    // ================================================================
    private final TenantRepository tenantRepository;
    private final ProvisioningTaskRepository provisioningTaskRepository;
    private final CompliancePolicyRepository compliancePolicyRepository;
    private final RecordingPolicyRepository recordingPolicyRepository;
    private final AgentCapacityRepository agentCapacityRepository;
    private final TenantAppRepository tenantAppRepository;
    private final TenantMapper tenantMapper;
    private final TenantStateMachine stateMachine;

    private final ReadinessCheckService readinessCheckService;
    private final TenantEventProducer eventProducer;
    private final ProductServiceClient productServiceClient;
    private final BillingServiceClient billingServiceClient;
    private  final ProvisioningOrchestratorImpl provisioningOrchestrator;
    private final ObjectMapper objectMapper;

    // TELECOM PROVISIONING
    private final KubernetesConfigDiscoveryService configDiscovery;
    private final TelecomProvisioningOrchestrator telecomProvisioner;

    private static final int TENANT_EXPIRY_HOURS = 24;

    // ================================================================
    // TENANT LIFECYCLE
    // ================================================================

    @Override
    public TenantResponse createTenant(CreateTenantRequest request, Jwt jwt) {
        String slug = SlugGenerator.generate(request.name());

        if (tenantRepository.existsBySlug(slug)) {
            throw new TenantAlreadyExistsException(slug);
        }

        Tenant tenant = tenantMapper.toEntity(request);
        tenant.setSlug(slug);
        tenant.setStatus(TenantStatus.CREATED);
        tenant.setAdminUserEmail(jwt.getSubject());
        tenant.setAdminUserId(jwt.getId());
        tenant.setCountry(request.country() != null ? request.country() : "IN");
        tenant.setTimezone(request.timezone() != null ? request.timezone() : "Asia/Kolkata");
        tenant.setExpiresAt(OffsetDateTime.now().plusHours(TENANT_EXPIRY_HOURS));

        tenant = tenantRepository.save(tenant);


        try {
            provisioningOrchestrator.setIdentityProvisioner(tenant);
            stateMachine.transition(tenant, TenantStatus.IDENTITY_CREATED, "SYSTEM", "Keycloak setup verified");
        } catch (Exception e) {
            log.error("Keycloak provisioning failed for tenant {}: {}", tenant.getId(), e.getMessage());
            stateMachine.transition(tenant, TenantStatus.ERROR, "KEYCLOAK_SERVICE", "Keycloak setup failed: " + e.getMessage());
        }

        billingServiceClient.createWallet(tenant.getId());
        stateMachine.transition(tenant, TenantStatus.WALLET_CREATED, "SYSTEM", "Tenant Wallet Created");

        eventProducer.publish("tenant.created", tenant.getId().toString(),
                new TenantCreatedEvent(tenant.getId(), tenant.getSlug()));

        log.info("Created tenant: {} ({})", tenant.getName(), tenant.getSlug());
        return tenantMapper.toResponse(tenant);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantResponse> listTenants() {
        return tenantRepository.findAll().stream()
                .filter(t -> t.getDeletedAt() == null)
                .map(tenantMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TenantResponse getTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .map(tenantMapper::toResponse)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public TenantDetailResponse getTenantDetails(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .map(tenantMapper::toDetailResponse)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
    }

    @Override
    public TenantResponse updateTenant(UUID tenantId, UpdateTenantRequest request) {
        Tenant tenant = findTenantOrThrow(tenantId);
        tenantMapper.updateTenantFromRequest(request, tenant);
        tenant = tenantRepository.save(tenant);
        log.info("Updated tenant: {}", tenantId);
        return tenantMapper.toResponse(tenant);
    }

    @Override
    public void activateTenant(UUID tenantId) {
        Tenant tenant = findTenantOrThrow(tenantId);
        stateMachine.transition(tenant, TenantStatus.ACTIVE, "ADMIN", "Reactivated by admin");
        log.info("Activated tenant: {}", tenantId);
    }

    @Override
    public void suspendTenant(UUID tenantId, String reason) {
        Tenant tenant = findTenantOrThrow(tenantId);
        stateMachine.transition(tenant, TenantStatus.SUSPENDED, "ADMIN", reason);
        log.info("Suspended tenant: {} - {}", tenantId, reason);
    }

    @Override
    public void deleteTenant(UUID tenantId, String reason) {
        Tenant tenant = findTenantOrThrow(tenantId);

        // Deprovision all subscriptions
        List<TenantApp> apps = tenantAppRepository.findByTenantId(tenantId);
        for (TenantApp app : apps) {
            try {
                telecomProvisioner.deprovisionTelecomStack(app.getSubscriptionId());
            } catch (Exception e) {
                log.error("Failed to deprovision subscription {}: {}", app.getSubscriptionId(), e.getMessage());
            }
        }

        stateMachine.transition(tenant, TenantStatus.DELETED, "ADMIN", reason);
        eventProducer.publish("tenant.deleted", tenantId.toString(), new TenantDeletedEvent(tenantId));
        log.info("Deleted tenant: {}", tenantId);
    }

    // ================================================================
    // SUBSCRIPTION ACTIVATION - MAIN ENTRY POINT
    // ================================================================

    /**
     * Called by product-service when subscription becomes ACTIVE.
     *
     * Flow:
     * 1. Update tenant status (remove expiry, activate)
     * 2. Fetch COMPLETE config from product-service (single source of truth)
     * 3. Create TenantApp with all data
     * 4. Call TelecomProvisioningOrchestrator to configure entire stack
     */
    @Transactional
    public void onSubscriptionActive(UUID tenantId, SubscriptionActiveRequest request) {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║ SUBSCRIPTION ACTIVATION                                      ║");
        log.info("║ Tenant: {} | Subscription: {} | Product: {}",
                tenantId, request.subscriptionId(), request.productCode());
        log.info("╚══════════════════════════════════════════════════════════════╝");

        // 1. Find tenant
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new RuntimeException("Tenant not found: " + tenantId));

        // 2. Update tenant status
        updateTenantStatus(tenant);

        // 3. Fetch COMPLETE configuration from product-service
        ProductConfigResponse config = productServiceClient.getSubscriptionConfig(request.subscriptionId());

        log.info("Received config - product: {}, plan: {}, tier: {}, apps: {}",
                config.product().code(), config.plan().code(), config.plan().tier(),
                config.apps() != null ? config.apps().size() : 0);


        // 5. Determine deployment model FROM ENTITLEMENTS
        boolean isDedicated = config.entitlements().dedicatedInfrastructure();
        String namespace = isDedicated ? tenant.getSlug() : configDiscovery.getSharedNamespace();

        // 6. Create TenantApp
        TenantApp app = createOrUpdateTenantApp(tenant, request, config, namespace, isDedicated);

        // 7. CALL TELECOM PROVISIONING ORCHESTRATOR
        telecomProvisioner.provisionTelecomStack(app, config);
        // 4. Activate tenant
        activateTenantInternal(tenant);

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║ SUBSCRIPTION ACTIVATED                                       ║");
        log.info("║ Dashboard: {}", app.getDashboardUrl());
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }

    /**
     * Handle subscription cancellation
     */
    @Transactional
    public void onSubscriptionCancelled(UUID tenantId, UUID subscriptionId) {
        log.info("Processing subscription cancellation - tenant: {}, subscription: {}", tenantId, subscriptionId);
        telecomProvisioner.deprovisionTelecomStack(subscriptionId);
        log.info("Subscription {} cancelled", subscriptionId);
    }

    /**
     * Handle plan upgrade/downgrade
     */
    @Transactional
    public void onPlanChanged(UUID tenantId, UUID subscriptionId) {
        log.info("Processing plan change - subscription: {}", subscriptionId);

        TenantApp app = tenantAppRepository.findBySubscriptionId(subscriptionId)
                .orElseThrow(() -> new RuntimeException("TenantApp not found: " + subscriptionId));

        ProductConfigResponse newConfig = productServiceClient.getSubscriptionConfig(subscriptionId);

        setEntitlements(app, newConfig.entitlements());
        tenantAppRepository.save(app);

        telecomProvisioner.refreshTelecomConfig(app, newConfig);

        log.info("Plan changed - new plan: {}", newConfig.plan().code());
    }

    // ================================================================
    // PROVISIONING
    // ================================================================

    @Override
    public void triggerProvisioning(UUID tenantId) {

        log.info("Triggered provisioning for tenant: {}", tenantId);
    }

    @Override
    public void retryProvisioning(UUID tenantId) {

        log.info("Retrying provisioning for tenant: {}", tenantId);
    }

    @Override
    public void restartProvisioning(UUID tenantId, String reason) {
        Tenant tenant = findTenantOrThrow(tenantId);
        log.info("Initiating provisioning restart for tenant: {} due to: {}", tenantId, reason);
        stateMachine.transition(tenant, TenantStatus.PROVISIONING_RESTART, "ADMIN", reason);

    }

    @Override
    @Transactional(readOnly = true)
    public ProvisioningStatusResponse getProvisioningStatus(UUID tenantId) {
        return provisioningTaskRepository
                .findFirstByTenantIdAndStatusIn(tenantId,
                        List.of(ProvisioningTaskStatus.RUNNING, ProvisioningTaskStatus.PENDING, ProvisioningTaskStatus.FAILED))
                .map(tenantMapper::toProvisioningStatus)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public ReadinessCheckResponse checkReadiness(UUID tenantId) {
        return readinessCheckService.check(tenantId);
    }

    @Override
    public void setIdentity(UUID tenantId) {
        Tenant tenant = findTenantOrThrow(tenantId);

    }

    // ================================================================
    // KYC
    // ================================================================

    @Override
    public void approveKyc(UUID tenantId) {
        Tenant tenant = findTenantOrThrow(tenantId);
        stateMachine.transition(tenant, TenantStatus.KYC_APPROVED, "COMPLIANCE_OFFICER", "KYC documents verified");
        checkAndTransitionToReadyToProvision(tenant);
        log.info("KYC Approved for tenant: {}", tenantId);
    }

    @Override
    public void rejectKyc(UUID tenantId, String reason) {
        Tenant tenant = findTenantOrThrow(tenantId);
        stateMachine.transition(tenant, TenantStatus.KYC_REJECTED, "COMPLIANCE_OFFICER", reason);
        log.info("KYC Rejected for tenant: {}. Reason: {}", tenantId, reason);
    }

    // ================================================================
    // EVENT HANDLERS
    // ================================================================

    @Override
    public void onPlanAssigned(UUID tenantId, UUID planId, String planCode) {
        Tenant tenant = findTenantOrThrow(tenantId);
        TenantApp tenantApp = TenantApp.builder()
                .planCode(planCode)
                .planAssignedAt(OffsetDateTime.now())
                .planId(planId)
                .tenant(tenant)
                .build();
        tenantAppRepository.save(tenantApp);

        if (tenant.getStatus() == TenantStatus.CREATED) {
            stateMachine.transition(tenant, TenantStatus.PLAN_ASSIGNED, "PRODUCT_SERVICE", "Plan assigned: " + planCode);
        }
        tenantRepository.save(tenant);
        log.info("Plan assigned to tenant {}: {}", tenantId, planCode);
    }

    @Override
    public void onDidPurchased(UUID tenantId) {
        Tenant tenant = findTenantOrThrow(tenantId);
        if (tenant.getStatus() == TenantStatus.PLAN_ASSIGNED) {
            stateMachine.transition(tenant, TenantStatus.PRODUCTS_CONFIGURED, "PRODUCT_SERVICE", "DID purchased");
        }
        log.info("DID purchased for tenant: {}", tenantId);
    }

    @Override
    public void onWalletCreated(UUID tenantId, UUID walletId) {
        Tenant tenant = findTenantOrThrow(tenantId);
        tenant.setWalletId(walletId);
        tenantRepository.save(tenant);
        log.info("Wallet created for tenant {}: {}", tenantId, walletId);
    }

    @Override
    public void onWalletFunded(UUID tenantId) {
        Tenant tenant = findTenantOrThrow(tenantId);
        tenant.setBillingReadyAt(OffsetDateTime.now());

        if (tenant.getStatus() == TenantStatus.PRODUCTS_CONFIGURED) {
            stateMachine.transition(tenant, TenantStatus.BILLING_READY, "BILLING_SERVICE", "Wallet funded");
            checkAndTransitionToReadyToProvision(tenant);
        }
        log.info("Wallet funded for tenant: {}", tenantId);
    }

    @Override
    public void onBillingStateChanged(UUID tenantId, String state) {
        Tenant tenant = findTenantOrThrow(tenantId);
        tenant.setBillingState(state);
        tenantRepository.save(tenant);

        if ("BLOCKED".equals(state) && tenant.getStatus() == TenantStatus.ACTIVE) {
            stateMachine.transition(tenant, TenantStatus.SUSPENDED, "BILLING_SERVICE", "Billing blocked");
        }
        log.info("Billing state changed for tenant {}: {}", tenantId, state);
    }

    @Override
    @Transactional(readOnly = true)
    public TenantProvisioningConfigResponse getProvisioningConfig(UUID tenantId) {
        // Return provisioning config for infrastructure setup
        return null; // Implement based on your needs
    }

    // ================================================================
    // PRIVATE HELPERS
    // ================================================================

    private Tenant findTenantOrThrow(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
    }

    private void updateTenantStatus(Tenant tenant) {
        tenant.setExpiresAt(null);
        if (tenant.getStatus() == TenantStatus.CREATED ||
                tenant.getStatus() == TenantStatus.IDENTITY_CREATED ||
                tenant.getStatus() == TenantStatus.WALLET_CREATED) {
            tenant.setStatus(TenantStatus.ACTIVE);
            tenant.setActivatedAt(OffsetDateTime.now());
        }
        tenant.setUpdatedAt(OffsetDateTime.now());
        tenantRepository.save(tenant);
    }

    private void activateTenantInternal(Tenant tenant) {

        stateMachine.transition(tenant, TenantStatus.ACTIVE, "SUBSCRIPTION_ACTIVE", "Subscription activated");
        log.info("Activated tenant: {}", tenant.getId());
    }

    private void checkAndTransitionToReadyToProvision(Tenant tenant) {
        ReadinessCheckResponse readiness = readinessCheckService.check(tenant.getId());
        if (readiness.ready() && tenant.getStatus() == TenantStatus.BILLING_READY) {
            stateMachine.transition(tenant, TenantStatus.READY_TO_PROVISION, "SYSTEM", "All prerequisites met");
        }
    }

    // ================================================================
    // TENANT APP CREATION
    // ================================================================

    private TenantApp createOrUpdateTenantApp(
            Tenant tenant,
            SubscriptionActiveRequest req,
            ProductConfigResponse config,
            String namespace,
            boolean isDedicated) {

        String slug = tenant.getSlug();
        String baseDomain = configDiscovery.getBaseDomain();

        TenantApp app = tenantAppRepository.findBySubscriptionId(req.subscriptionId())
                .orElseGet(() -> TenantApp.builder()
                        .tenant(tenant)
                        .subscriptionId(req.subscriptionId())
                        .enabled(true)
                        .deploymentStatus(ProvisioningTaskStatus.PENDING)
                        .build());

        // DEPLOYMENT
        app.setDeploymentModel(isDedicated ? DeploymentModel.DEDICATED : DeploymentModel.SHARED);
        app.setNamespace(namespace);
        app.setDedicatedInfrastructure(isDedicated);
        app.setSlaTier(config.entitlements().slaTier());
        app.setCustomDomainEnabled(config.entitlements().customDomainEnabled());

        // PRODUCT & PLAN
        app.setProductCode(config.product().code());
        app.setPlanId(config.plan().id());
        app.setPlanCode(config.plan().code());
        app.setPlanTier(config.plan().tier());
        app.setPlanAssignedAt(OffsetDateTime.now());

        // APP METADATA
        ProductAppResponse primaryApp = getPrimaryApp(config.apps());
        app.setAppType(AppType.valueOf(primaryApp.appType()));
        app.setSubdomain(primaryApp.subdomain());
        app.setDisplayName(tenant.getName() + " - " + config.product().name());
        app.setDescription(config.product().description());
        app.setIcon(primaryApp.icon());
        app.setDisplayOrder(0);
        app.setRequiredRoles(primaryApp.requiredRoles());
        app.setFrontendImage(primaryApp.frontendImage());
        app.setFrontendPort(primaryApp.frontendPort());

        // SUBSCRIPTION-SPECIFIC
        app.setAgentSeats(req.agentSeats());

        // ENTITLEMENTS
        setEntitlements(app, config.entitlements());

        // AI CONFIG
        if (config.aiConfig() != null) {
            app.setAiRatePerMinute(config.aiConfig().totalAiCostPerMin());
        }

        // PROVISIONED RESOURCES
        setResources(app, req);

        // INFRASTRUCTURE URLs
        setInfrastructureUrls(app, slug, baseDomain, isDedicated);

        // KEYCLOAK & FRONTEND
        //app.setKeycloakClientId("dalaillama-" + slug);
        //app.setDashboardUrl("https://" + primaryApp.subdomain() + "." + slug + "." + baseDomain);
        //app.setFrontendService(slug + "-" + primaryApp.subdomain() + "-ui");

        // APP PANELS JSON
        if (config.apps() != null && !config.apps().isEmpty()) {
            app.setAppPanels(buildAppPanelsJson(config.apps(), slug, baseDomain));
        }

        // SYNC FLAGS
        app.setKamailioSynced(false);
        app.setFreepbxSynced(false);

        return tenantAppRepository.save(app);
    }

    private ProductAppResponse getPrimaryApp(List<ProductAppResponse> apps) {
        if (apps == null || apps.isEmpty()) {
            throw new RuntimeException("No apps configured for product. Check product_apps table.");
        }
        return apps.stream()
                .filter(a -> a.displayOrder() == 0)
                .findFirst()
                .orElse(apps.get(0));
    }

    private void setEntitlements(TenantApp app, PlanEntitlementResponse e) {
        // CAPACITY
        app.setMaxAgents(e.maxAgents());
        app.setMaxSupervisors(e.maxSupervisors());
        app.setMaxChannels(e.maxPstnChannels());
        app.setMaxDids(e.maxDids());
        app.setMaxQueues(e.maxQueues());
        app.setMaxIvrFlows(e.maxIvrFlows());
        app.setMaxRingGroups(e.maxRingGroups());
        app.setMaxExtensions(e.maxExtensions());

        // AI FEATURES
        app.setAiTranscriptionEnabled(e.aiSttEnabled());
        app.setAiRoutingEnabled(e.aiRoutingEnabled());
        app.setAiSentimentEnabled(e.aiSentimentEnabled());
        app.setAiBotEnabled(e.aiBotEnabled());
        app.setAiNoiseCancellationEnabled(e.aiNoiseCancellationEnabled());
        app.setAiVoiceMorphEnabled(e.aiVoiceMorphEnabled());
        app.setAiAgentAssistEnabled(e.aiAgentAssistEnabled());
        app.setAiTokensPerMonth((int) e.aiTokensPerMonth());

        // CALL FEATURES
        app.setBargeEnabled(e.bargeEnabled());
        app.setWhisperEnabled(e.whisperEnabled());
        app.setListenEnabled(e.listenEnabled());
        app.setConferenceEnabled(e.conferenceEnabled());
        app.setCallbackEnabled(e.callbackEnabled());
        app.setBlindTransferEnabled(e.blindTransferEnabled());
        app.setAttendedTransferEnabled(e.attendedTransferEnabled());
        app.setWarmTransferEnabled(e.warmTransferEnabled());

        // RECORDING
        app.setRecordingEnabled(e.recordingEnabled());
        app.setRecordingStorageGb(e.recordingStorageGb());
        app.setRecordingRetentionDays(e.recordingRetentionDays());
        app.setScreenRecordingEnabled(e.screenRecordingEnabled());

        // IVR
        app.setBasicIvrEnabled(e.basicIvrEnabled());
        app.setConversationalIvrEnabled(e.conversationalIvrEnabled());
        app.setIvrMultiLanguageEnabled(e.ivrMultiLanguageEnabled());

        // DIALER
        app.setProgressiveDialerEnabled(e.progressiveDialerEnabled());
        app.setPredictiveDialerEnabled(e.predictiveDialerEnabled());
        app.setPreviewDialerEnabled(e.previewDialerEnabled());
        app.setAmdEnabled(e.amdEnabled());
        app.setDncManagementEnabled(e.dncManagementEnabled());

        // VOICEMAIL
        app.setVoicemailEnabled(e.voicemailEnabled());
        app.setVoicemailTranscriptionEnabled(e.voicemailTranscriptionEnabled());

        // INTEGRATIONS
        app.setCrmIntegrationEnabled(e.crmIntegrationEnabled());
        app.setScreenPopEnabled(e.screenPopEnabled());
        app.setApiAccessEnabled(e.apiAccessEnabled());
        app.setWebhookEnabled(e.webhookEnabled());

        // REPORTING
        app.setBasicReportingEnabled(e.basicReportingEnabled());
        app.setAdvancedReportingEnabled(e.advancedReportingEnabled());
        app.setCustomReportsEnabled(e.customReportsEnabled());
        app.setWallboardEnabled(e.wallboardEnabled());

        // USAGE LIMITS
        app.setIncludedMinutesInbound(e.includedMinutesInbound());
        app.setIncludedMinutesOutbound(e.includedMinutesOutbound());
        app.setRatePerMinuteInbound(e.ratePerMinuteInbound());
        app.setRatePerMinuteOutbound(e.ratePerMinuteOutbound());
        app.setAiRatePerMinute(e.aiRatePerMinute());
    }

    private void setResources(TenantApp app, SubscriptionActiveRequest req) {
        if (req.did() != null) {
            app.setDidId(req.did().id());
            app.setDidNumber(req.did().number());
            app.setDidDisplayNumber(req.did().displayNumber());
            app.setDidCountry(req.did().country());
            app.setDidRegion(req.did().region());
            app.setDidCity(req.did().city());
        }

        if (req.sipEndpoint() != null) {
            app.setSipEndpointId(req.sipEndpoint().id());
            app.setSipEndpointUsername(req.sipEndpoint().username());
            app.setSipEndpointPasswordHash(req.sipEndpoint().passwordHash());
            app.setSipEndpointDomain(req.sipEndpoint().domain());
            app.setSipEndpointRealm(req.sipEndpoint().realm());
        }

        if (req.channels() != null) {
            app.setChannelBundleId(req.channels().id());
            app.setChannelDirection(req.channels().direction());
            app.setChannelTotal(req.channels().total());
            app.setChannelInbound(req.channels().inbound());
            app.setChannelOutbound(req.channels().outbound());
        }

        if (req.tenantSipTrunk() != null) {
            app.setTenantTrunkId(req.tenantSipTrunk().id());
            app.setTenantTrunkUsername(req.tenantSipTrunk().username());
            app.setTenantTrunkPasswordHash(req.tenantSipTrunk().passwordHash());
            app.setTenantTrunkDomain(req.tenantSipTrunk().domain());
            app.setTenantTrunkPort(req.tenantSipTrunk().port());
            app.setTenantTrunkRealm(req.tenantSipTrunk().realm());
            app.setTenantTrunkTransport(req.tenantSipTrunk().transport());
            app.setTenantTrunkMaxCalls(req.tenantSipTrunk().maxConcurrentCalls());
        }

        if (req.platformTrunk() != null) {
            app.setPlatformTrunkId(req.platformTrunk().id());
            app.setPlatformTrunkProvider(req.platformTrunk().provider());
            app.setPlatformTrunkServer(req.platformTrunk().server());
            app.setPlatformTrunkPort(req.platformTrunk().port());
            app.setPlatformTrunkTransport(req.platformTrunk().transport());
            app.setPlatformTrunkCodecs(req.platformTrunk().codecs());
        }
    }

    private void setInfrastructureUrls(TenantApp app, String slug, String baseDomain, boolean isDedicated) {
        if (isDedicated) {
            var infra = configDiscovery.buildDedicatedEndpoints(slug);
            app.setPostgresUrl(infra.postgresUrl());
            app.setRedisUrl(infra.redisUrl());
            app.setKafkaBootstrap(infra.kafkaBootstrap());
            app.setSipExternalIp(infra.sipExternalIp());
            app.setSipUdpUrl("sip:" + infra.sipExternalIp() + ":5060");
            app.setSipTlsUrl("sips:" + infra.sipExternalIp() + ":5061");
            app.setTurnUrl("turn:" + infra.turnHost() + ":3478");
            app.setWebsocketUrl(infra.websocketUrl());
            app.setRtpengineSock(infra.rtpengineSocket());
            app.setFreeswitchEslHost(infra.freeswitchHost());
            app.setFreeswitchEslPort(8021);
            app.setFreeswitchEslPassword(infra.freeswitchEslPassword());
        } else {
            app.setPostgresUrl(configDiscovery.getSharedPostgresUrl() + "?currentSchema=" + slug);
            app.setRedisUrl(configDiscovery.getSharedRedisUrl());
            app.setKafkaBootstrap(configDiscovery.getSharedKafkaBootstrap());
            String sipHost = configDiscovery.getSharedSipHost();
            app.setSipExternalIp(sipHost);
            app.setSipUdpUrl("sip:" + sipHost + ":5060");
            app.setSipTlsUrl("sips:" + sipHost + ":5061");
            app.setTurnUrl("turn:" + configDiscovery.getSharedTurnHost() + ":3478");
            app.setWebsocketUrl("wss://ws." + baseDomain + "/ws");
            app.setRtpengineSock(configDiscovery.getSharedRtpengineSocket());
            app.setFreeswitchEslHost(configDiscovery.getSharedFreeswitchHost());
            app.setFreeswitchEslPort(configDiscovery.getSharedFreeswitchEslPort());
            app.setFreeswitchEslPassword(configDiscovery.getSharedFreeswitchEslPassword());
        }
    }

    private String buildAppPanelsJson(List<ProductAppResponse> apps, String tenantSlug, String baseDomain) {
        try {
            List<AppPanelDto> panels = new ArrayList<>();

            for (var appInfo : apps) {
                // URL: admin-acme.dalaillama.in
                String url = "https://" + appInfo.subdomain() + "-" + tenantSlug + "." + baseDomain;

                // ClientId: admin-ui, agent-ui, supervisor-ui
                String clientId = appInfo.subdomain() + "-ui";

                panels.add(new AppPanelDto(
                        appInfo.appType(), appInfo.displayName(), appInfo.subdomain(), url,
                        appInfo.icon(), appInfo.displayOrder(), clientId,
                        appInfo.frontendImage(), appInfo.requiredRoles()
                ));
            }
            return objectMapper.writeValueAsString(panels);
        } catch (Exception e) {
            log.error("Failed to build app panels JSON", e);
            return "[]";
        }
    }


    record AppPanelDto(
            String appType, String displayName, String subdomain, String url,
            String icon, int displayOrder, String keycloakClientId,
            String frontendImage, String requiredRoles
    ) {}
}