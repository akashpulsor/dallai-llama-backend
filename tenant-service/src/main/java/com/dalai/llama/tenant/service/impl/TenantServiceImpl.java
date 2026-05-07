package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import com.dalai.llama.tenant.domain.event.TenantCreatedEvent;
import com.dalai.llama.tenant.domain.event.TenantDeletedEvent;
import com.dalai.llama.tenant.domain.event.WalletCreditedEvent;
import com.dalai.llama.tenant.domain.event.WalletExternalEvent;
import com.dalai.llama.tenant.domain.exception.TenantAlreadyExistsException;
import com.dalai.llama.tenant.domain.exception.TenantNotFoundException;
import com.dalai.llama.tenant.dto.mapper.TenantMapper;
import com.dalai.llama.tenant.dto.request.CreateTenantRequest;
import com.dalai.llama.tenant.dto.request.UpdateTenantRequest;
import com.dalai.llama.tenant.dto.response.*;
import com.dalai.llama.tenant.kafka.producer.TenantEventProducer;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.service.KeycloakRealmService;
import com.dalai.llama.tenant.service.ProvisioningOrchestrator;
import com.dalai.llama.tenant.service.TenantService;
import com.dalai.llama.tenant.service.TenantStateMachine;
import com.dalai.llama.tenant.service.client.BillingServiceClient;
import com.dalai.llama.tenant.service.client.ProductServiceClient;
import com.dalai.llama.tenant.util.SlugGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.AppType;
import com.dalai.llama.tenant.domain.entity.enums.DeploymentModel;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.dto.request.ProductAppInfo;
import com.dalai.llama.tenant.dto.request.SubscriptionActiveRequest;
//import com.dalai.llama.tenant.dto.response.SubscriptionActiveResponse.AdminCredentials;
//import com.dalai.llama.tenant.dto.response.SubscriptionActiveResponse.AppInfo;
//import com.dalai.llama.tenant.dto.response.SubscriptionActiveResponse.ConfigStatus;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService {

    private final TenantRepository tenantRepository;
    private final TenantMapper tenantMapper;
    private final TenantStateMachine stateMachine;
    private final KeycloakRealmService keycloakRealmService;
    private final BillingServiceClient billingServiceClient;
    private final ProductServiceClient productServiceClient;
    private final TenantEventProducer eventProducer;
    private final ProvisioningOrchestrator provisioningOrchestrator;

    private final TenantWebSocketPublisher webSocketPublisher;

    private final TenantAppRepository tenantAppRepository;
    private final ObjectMapper objectMapper;


    @Value("${infra.base-domain:dalaillama.in}")
    private String domain;

    private static final int TENANT_EXPIRY_HOURS = 24;

    // ================================================================
    // CREATE TENANT
    //
    // If the same user has an incomplete tenant, resume it.
    // Otherwise create a new one and provision.
    //
    // Flow: CREATED → IDENTITY_CREATED → WALLET_CREATED
    // ================================================================

    @Override
    @Transactional(readOnly = true)
    public Optional<Tenant> findByAdminUserId(String adminUserId) {
        return tenantRepository.findFirstByAdminUserIdAndDeletedAtIsNull(adminUserId);
    }

    @Override
    @Transactional
    public TenantResponse createTenant(CreateTenantRequest request, Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        String jwtEmail = jwt.getClaimAsString("email");


        // ── Verify user exists in Keycloak (dalai-llama realm) ──
        UserRepresentation kcUser = keycloakRealmService.getPlatformUser(keycloakUserId);

        if (!Boolean.TRUE.equals(kcUser.isEnabled())) {
            throw new IllegalStateException("User " + keycloakUserId + " is not enabled in Keycloak");
        }

        if (!keycloakUserId.equals(kcUser.getId())) {
            log.error("JWT sub mismatch! jwt.sub={} kc.id={}", keycloakUserId, kcUser.getId());
            throw new IllegalStateException("JWT subject does not match Keycloak user ID");
        }

        // Use Keycloak as source of truth for email/name
        String email = kcUser.getEmail() != null ? kcUser.getEmail() : jwtEmail;
        String firstName = kcUser.getFirstName();
        String lastName = kcUser.getLastName();

        log.info("Creating tenant for verified Keycloak user id={} email={}", keycloakUserId, email);

        // ── Resume incomplete tenant if exists (lookup by ID, not email) ──
        Tenant existing = tenantRepository.findFirstByAdminUserIdAndStatusInOrderByCreatedAtDesc(
                keycloakUserId,
                List.of(TenantStatus.CREATED, TenantStatus.IDENTITY_CREATED, TenantStatus.WALLET_CREATED)
        ).orElse(null);

        if (existing != null) {
            log.info("Resuming incomplete tenant {} for user {}", existing.getId(), keycloakUserId);
            return provisionTenant(existing);
        }

        // ── Create new tenant ──
        String slug = SlugGenerator.generate(request.name());
        if (tenantRepository.existsBySlug(slug)) {
            slug = slug + "-" + UUID.randomUUID().toString().substring(0, 4);
        }

        Tenant tenant = tenantMapper.toEntity(request);
        tenant.setSlug(slug);
        tenant.setStatus(TenantStatus.CREATED);
        tenant.setAdminUserId(keycloakUserId);
        tenant.setAdminUserEmail(email);
        tenant.setCountry(request.country() != null ? request.country() : "IN");
        tenant.setTimezone(request.timezone() != null ? request.timezone() : "Asia/Kolkata");
        tenant.setExpiresAt(OffsetDateTime.now().plusHours(TENANT_EXPIRY_HOURS));

        // Populate primary contact from Keycloak if not provided
        if (tenant.getPrimaryContactName() == null && firstName != null) {
            String fullName = (firstName + " " + (lastName != null ? lastName : "")).trim();
            tenant.setPrimaryContactName(fullName);
        }
        if (tenant.getPrimaryContactEmail() == null) {
            tenant.setPrimaryContactEmail(email);
        }

        tenant = tenantRepository.save(tenant);
        log.info("Created tenant record: {} ({})", tenant.getName(), slug);

        return provisionTenant(tenant);
    }

    private TenantResponse provisionTenant(Tenant tenant) {

        // Step 1: Keycloak realm
        if (tenant.getStatus() == TenantStatus.CREATED) {
            try {
                String realmName = "tenant-" + tenant.getId();
                if (!keycloakRealmService.realmExists(realmName)) {
                    keycloakRealmService.createRealm(realmName, tenant.getName());
                }
                keycloakRealmService.createRealm(realmName, tenant.getName());

                // Fetch the created realm's internal UUID
                String realmId = keycloakRealmService.getRealmId(realmName);

                tenant.setKeycloakRealmName(realmName);
                tenant.setKeycloakRealmId(realmId);
                tenantRepository.save(tenant);

                log.info("Keycloak realm created — name={} id={}", realmName, realmId);

                stateMachine.transition(tenant, TenantStatus.IDENTITY_CREATED,
                        "SYSTEM", "Keycloak realm created");
            } catch (Exception e) {
                log.error("Keycloak failed for tenant {}: {}", tenant.getId(), e.getMessage());
                tenant.setStatusMessage(truncate("Keycloak failed: " + e.getMessage(), 1990));
                tenantRepository.save(tenant);
                return tenantMapper.toResponse(tenant);
            }
        }

        // Step 2: Billing wallet
        if (tenant.getStatus() == TenantStatus.IDENTITY_CREATED) {
            try {
                billingServiceClient.createWallet(tenant.getId());
                stateMachine.transition(tenant, TenantStatus.WALLET_CREATED,
                        "SYSTEM", "Wallet created");
            } catch (Exception e) {
                log.error("Wallet failed for tenant {}: {}", tenant.getId(), e.getMessage());
                rollbackKeycloak(tenant);
                tenant.setStatus(TenantStatus.CREATED);
                tenant.setStatusMessage(truncate("Wallet failed: " + e.getMessage(), 1990));
                tenantRepository.save(tenant);
                return tenantMapper.toResponse(tenant);
            }
        }

        eventProducer.publishTenantCreated(new TenantCreatedEvent(tenant.getId(), tenant.getSlug()));
        log.info("Tenant provisioning complete: {} ({})", tenant.getName(), tenant.getSlug());
        return tenantMapper.toResponse(tenant);
    }
    // ================================================================
    // CRUD
    // ================================================================

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
    public Tenant getTenantData(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
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
        return tenantMapper.toResponse(tenant);
    }

    @Override
    public void activateTenant(UUID tenantId) {
        Tenant tenant = findTenantOrThrow(tenantId);
        stateMachine.transition(tenant, TenantStatus.ACTIVE, "ADMIN", "Activated by admin");
    }

    @Override
    public void suspendTenant(UUID tenantId, String reason) {
        Tenant tenant = findTenantOrThrow(tenantId);
        stateMachine.transition(tenant, TenantStatus.SUSPENDED, "ADMIN", reason);
    }

    @Override
    public void deleteTenant(UUID tenantId, String reason) {
        Tenant tenant = findTenantOrThrow(tenantId);
        rollbackKeycloak(tenant);
        stateMachine.transition(tenant, TenantStatus.DELETED, "ADMIN", reason);
        eventProducer.publishTenantDeleted(new TenantDeletedEvent(tenantId));
    }

    // ================================================================
    // EVENT HANDLERS (from Kafka)
    // ================================================================

    @Override
    public void onWalletCreated(UUID tenantId, UUID walletId) {
        Tenant tenant = findTenantOrThrow(tenantId);
        tenant.setWalletId(walletId);
        tenantRepository.save(tenant);

        webSocketPublisher.publish(
                tenantId,
                "wallet",
                WalletExternalEvent.builder()
                        .eventType("WALLET_CREATED")
                        .data(Map.of(
                                "event", "WALLET_CREATED",
                                "tenant_id", tenantId,
                                "wallet_id", walletId
                        ))
                        .build()
        );

        log.info("Wallet linked for tenant {}: {}", tenantId, walletId);
    }
    @Override
    public void onWalletFunded(WalletCreditedEvent walletCreditedEvent) {
        Tenant tenant = findTenantOrThrow(walletCreditedEvent.getTenantId());
        tenant.setBillingReadyAt(OffsetDateTime.now());
        tenantRepository.save(tenant);
        log.info("Wallet funded for tenant: {}", walletCreditedEvent.getTenantId());

        webSocketPublisher.publish(
                walletCreditedEvent.getTenantId(),
                "wallet",
                WalletExternalEvent.builder()
                        .eventType("WALLET_FUNDED")
                        .data(walletCreditedEvent)
                        .build()
        );
    }

    @Override
    public void onBillingStateChanged(UUID tenantId, String state) {
        Tenant tenant = findTenantOrThrow(tenantId);
        tenant.setBillingState(state);
        tenantRepository.save(tenant);

        webSocketPublisher.publish(
                tenantId,
                "billing",
                Map.of(
                        "event", "BILLING_STATE_CHANGED",
                        "tenant_id", tenantId,
                        "state", state
                )
        );

        if ("BLOCKED".equals(state)
                && tenant.getStatus() == TenantStatus.ACTIVE) {

            stateMachine.transition(
                    tenant,
                    TenantStatus.SUSPENDED,
                    "BILLING_SERVICE",
                    "Billing blocked"
            );

            webSocketPublisher.publish(
                    tenantId,
                    "notifications",
                    Map.of(
                            "event", "TENANT_SUSPENDED",
                            "tenant_id", tenantId,
                            "reason", "Billing blocked"
                    )
            );
        }
    }


    @Override
    @Transactional
    public SubscriptionActiveResponse activateSubscription(SubscriptionActiveRequest req) {

        Tenant tenant = findTenantOrThrow(req.tenantId());

        // Idempotency
        Optional<TenantApp> existing = tenantAppRepository.findBySubscriptionId(req.subscriptionId());
        if (existing.isPresent()) {
            log.warn("Subscription {} already activated", req.subscriptionId());
            return buildActivationResponse(tenant, existing.get());
        }

        // Build TenantApp
        TenantApp app = buildTenantApp(tenant, req);
        tenantAppRepository.save(app);

        log.info("Created TenantApp {} for tenant={} subscription={} product={}",
                app.getId(), req.tenantId(), req.subscriptionId(), req.productCode());

        // Transition: WALLET_CREATED → PROVISIONING (infra not ready yet)
        stateMachine.transition(tenant, TenantStatus.PROVISIONING,
                "PRODUCT_SERVICE", "Subscription activated, provisioning starting: " + req.productCode());

        webSocketPublisher.publish(
                tenant.getId(),
                "apps",
                Map.of(
                        "event", "SUBSCRIPTION_ACTIVATED",
                        "tenant_id", tenant.getId(),
                        "tenant_app_id", app.getId(),
                        "subscription_id", app.getSubscriptionId(),
                        "product_code", app.getProductCode(),
                        "status", "PROVISIONING"
                )
        );

        // Trigger async provisioning after transaction commits
        UUID appId = app.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("Triggering provisioning for TenantApp={}", appId);
                provisioningOrchestrator.provision(appId);
            }
        });

        return buildActivationResponse(tenant, app);
    }

    // ── TenantApp builder — delegates to small mappers ──────────

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    private TenantApp buildTenantApp(Tenant tenant, SubscriptionActiveRequest req) {
        TenantApp.TenantAppBuilder builder = TenantApp.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .deploymentStatus(ProvisioningTaskStatus.PENDING)
                .kamailioSynced(false)
                .freepbxSynced(false)
                .enabled(true)
                .displayOrder(0);

        mapSubscription(builder, tenant, req);
        mapPlanReference(builder, req);
        mapEntitlements(builder, req);
        mapDid(builder, req);
        mapSipEndpoint(builder, req);
        mapChannels(builder, req);
        mapTenantTrunk(builder, req);
        mapPlatformTrunk(builder, req);
        mapDashboard(builder, tenant, req);

        return builder.build();
    }

    private void mapSubscription(TenantApp.TenantAppBuilder b, Tenant tenant, SubscriptionActiveRequest req) {
        b.subscriptionId(req.subscriptionId())
                .appType(parseAppType(req.productCode()))
                .subdomain(tenant.getSlug())
                .displayName(req.productName());
    }

    private void mapPlanReference(TenantApp.TenantAppBuilder b, SubscriptionActiveRequest req) {
        b.productCode(req.productCode())
                .planId(req.planId())
                .planCode(req.planCode())
                .planTier(req.planTier())
                .planAssignedAt(OffsetDateTime.now());
    }

    private void mapEntitlements(TenantApp.TenantAppBuilder b, SubscriptionActiveRequest req) {
        // Basic entitlements from activation request
        b.agentSeats(req.agentSeats())
                .maxAgents(req.maxAgents())
                .maxDids(req.maxDids())
                .maxChannels(req.maxChannels())
                .includedMinutes(req.includedMinutes())
                .aiRatePerMinute(req.aiRatePerMin());

        // Fetch full feature flags from product-service and stamp ALL entitlements
        if (req.planId() != null) {
            try {
                PlanEntitlementResponse ent = productServiceClient.getPlanEntitlements(req.planId());
                if (ent != null) {
                    stampFullEntitlements(b, ent);
                    log.info("Full entitlements stamped from plan {} at activation", req.planCode());
                }
            } catch (Exception e) {
                log.warn("Failed to fetch entitlements for plan {} at activation — will resolve during provisioning: {}",
                        req.planId(), e.getMessage());
            }
        }
    }

    /**
     * Stamp ALL feature flags from PlanEntitlementResponse onto TenantApp.
     * Called at activation time so TenantApp has complete entitlements immediately.
     * Also called during provisioning (stepFetchEntitlements) as a safety net.
     */
    private void stampFullEntitlements(TenantApp.TenantAppBuilder b, PlanEntitlementResponse ent) {
        // Capacity
        b.maxSupervisors(ent.maxSupervisors())
                .maxQueues(ent.maxQueues())
                .maxIvrFlows(ent.maxIvrFlows())
                .maxRingGroups(ent.maxRingGroups())
                .maxExtensions(ent.maxExtensions());

        // Usage limits
        b.includedMinutesInbound(ent.includedMinutesInbound())
                .includedMinutesOutbound(ent.includedMinutesOutbound())
                .ratePerMinuteInbound(ent.ratePerMinuteInbound())
                .ratePerMinuteOutbound(ent.ratePerMinuteOutbound());

        // Recording
        b.recordingEnabled(ent.recordingEnabled())
                .screenRecordingEnabled(ent.screenRecordingEnabled())
                .recordingStorageGb(ent.recordingStorageGb())
                .recordingRetentionDays(ent.recordingRetentionDays());

        // AI features
        b.aiBotEnabled(ent.aiBotEnabled())
                .aiTranscriptionEnabled(ent.aiSttEnabled())
                .aiRoutingEnabled(ent.aiRoutingEnabled())
                .aiSentimentEnabled(ent.aiSentimentEnabled())
                .aiNoiseCancellationEnabled(ent.aiNoiseCancellationEnabled())
                .aiVoiceMorphEnabled(ent.aiVoiceMorphEnabled())
                .aiAgentAssistEnabled(ent.aiAgentAssistEnabled())
                .aiTokensPerMonth((int) ent.aiTokensPerMonth());

        // Call features
        b.bargeEnabled(ent.bargeEnabled())
                .whisperEnabled(ent.whisperEnabled())
                .listenEnabled(ent.listenEnabled())
                .conferenceEnabled(ent.conferenceEnabled())
                .callbackEnabled(ent.callbackEnabled())
                .blindTransferEnabled(ent.blindTransferEnabled())
                .attendedTransferEnabled(ent.attendedTransferEnabled())
                .warmTransferEnabled(ent.warmTransferEnabled());

        // IVR
        b.basicIvrEnabled(ent.basicIvrEnabled())
                .conversationalIvrEnabled(ent.conversationalIvrEnabled())
                .ivrMultiLanguageEnabled(ent.ivrMultiLanguageEnabled());

        // Dialer
        b.progressiveDialerEnabled(ent.progressiveDialerEnabled())
                .predictiveDialerEnabled(ent.predictiveDialerEnabled())
                .previewDialerEnabled(ent.previewDialerEnabled())
                .amdEnabled(ent.amdEnabled())
                .dncManagementEnabled(ent.dncManagementEnabled());

        // Voicemail
        b.voicemailEnabled(ent.voicemailEnabled())
                .voicemailTranscriptionEnabled(ent.voicemailTranscriptionEnabled());

        // Integrations
        b.crmIntegrationEnabled(ent.crmIntegrationEnabled())
                .screenPopEnabled(ent.screenPopEnabled())
                .apiAccessEnabled(ent.apiAccessEnabled())
                .webhookEnabled(ent.webhookEnabled());

        // Reporting
        b.basicReportingEnabled(ent.basicReportingEnabled())
                .advancedReportingEnabled(ent.advancedReportingEnabled())
                .customReportsEnabled(ent.customReportsEnabled())
                .wallboardEnabled(ent.wallboardEnabled());

        // Deployment
        b.dedicatedInfrastructure(ent.dedicatedInfrastructure())
                .customDomainEnabled(ent.customDomainEnabled())
                .slaTier(ent.slaTier());
    }

    private void mapDid(TenantApp.TenantAppBuilder b, SubscriptionActiveRequest req) {
        if (req.did() == null) return;
        b.didId(req.did().id())
                .didNumber(req.did().number())
                .didDisplayNumber(req.did().displayNumber())
                .didCountry(req.did().country())
                .didRegion(req.did().region())
                .didCity(req.did().city());
    }

    private void mapSipEndpoint(TenantApp.TenantAppBuilder b, SubscriptionActiveRequest req) {
        if (req.sipEndpoint() == null) return;
        b.sipEndpointId(req.sipEndpoint().id())
                .sipEndpointUsername(req.sipEndpoint().username())
                .sipEndpointPasswordHash(req.sipEndpoint().passwordHash())
                .sipEndpointDomain(req.sipEndpoint().domain())
                .sipEndpointRealm(req.sipEndpoint().realm());
    }

    private void mapChannels(TenantApp.TenantAppBuilder b, SubscriptionActiveRequest req) {
        if (req.channels() == null) return;
        b.channelBundleId(req.channels().id())
                .channelDirection(req.channels().direction())
                .channelTotal(req.channels().total())
                .channelInbound(req.channels().inbound())
                .channelOutbound(req.channels().outbound());
    }

    private void mapTenantTrunk(TenantApp.TenantAppBuilder b, SubscriptionActiveRequest req) {
        if (req.tenantSipTrunk() == null) return;
        b.tenantTrunkId(req.tenantSipTrunk().id())
                .tenantTrunkUsername(req.tenantSipTrunk().username())
                .tenantTrunkPasswordHash(req.tenantSipTrunk().passwordHash())
                .tenantTrunkDomain(req.tenantSipTrunk().domain())
                .tenantTrunkPort(req.tenantSipTrunk().port())
                .tenantTrunkRealm(req.tenantSipTrunk().realm())
                .tenantTrunkTransport(req.tenantSipTrunk().transport())
                .tenantTrunkMaxCalls(req.tenantSipTrunk().maxConcurrentCalls());
    }

    private void mapPlatformTrunk(TenantApp.TenantAppBuilder b, SubscriptionActiveRequest req) {
        if (req.platformTrunk() == null) return;
        b.platformTrunkId(req.platformTrunk().id())
                .platformTrunkProvider(req.platformTrunk().provider())
                .platformTrunkServer(req.platformTrunk().server())
                .platformTrunkPort(req.platformTrunk().port())
                .platformTrunkTransport(req.platformTrunk().transport())
                .platformTrunkCodecs(req.platformTrunk().codecs());
    }

    private void mapDashboard(TenantApp.TenantAppBuilder b, Tenant tenant, SubscriptionActiveRequest req) {
        String primaryUrl = buildDashboardUrl(
                req.productApps() != null && !req.productApps().isEmpty()
                        ? req.productApps().get(0).subdomain() : "app",
                tenant.getSlug());

        b.dashboardUrl(primaryUrl)
                .appPanels(buildAppPanelsJson(req.productApps(), tenant.getSlug()));
    }

    // ── URL + JSON helpers ──────────────────────────────────────

    private String buildDashboardUrl(String subdomain, String tenantSlug) {
        return "https://" + subdomain + "-" + tenantSlug + "." + domain;
    }

    private String buildAppPanelsJson(List<ProductAppInfo> apps, String tenantSlug) {
        if (apps == null || apps.isEmpty()) return "[]";

        List<Map<String, Object>> panels = apps.stream()
                .map(app -> Map.<String, Object>of(
                        "type", app.appType(),
                        "name", app.displayName(),
                        "url", buildDashboardUrl(app.subdomain(), tenantSlug),
                        "icon", app.icon() != null ? app.icon() : "",
                        "order", app.displayOrder()))
                .toList();
        try {
            return objectMapper.writeValueAsString(panels);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize appPanels", e);
            return "[]";
        }
    }

    // ── Response builder ────────────────────────────────────────

    private SubscriptionActiveResponse buildActivationResponse(Tenant tenant, TenantApp app) {
        List<AppInfo> appInfos = parseAppPanels(app);

        return SubscriptionActiveResponse.builder()
                .tenantId(tenant.getId())
                .tenantAppId(app.getId())
                .subscriptionId(app.getSubscriptionId())
                .status("ACTIVE")
                .apps(appInfos)
                .configStatus(ConfigStatus.builder()
                        .kamailioConfigured(false)
                        .freepbxConfigured(false)
                        .message("TenantApp created. Call /apps/" + app.getId() + "/provision to complete setup.")
                        .build())
                .build();
    }

    private List<AppInfo> parseAppPanels(TenantApp app) {
        try {
            List<Map<String, Object>> panels = objectMapper.readValue(
                    app.getAppPanels() != null ? app.getAppPanels() : "[]",
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));

            return panels.stream()
                    .map(p -> AppInfo.builder()
                            .id(app.getId())
                            .appType((String) p.get("type"))
                            .displayName((String) p.get("name"))
                            .url((String) p.get("url"))
                            .icon((String) p.get("icon"))
                            .build())
                    .toList();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse appPanels", e);
            return List.of();
        }
    }

    // ── Type parsers ────────────────────────────────────────────

    private AppType parseAppType(String productCode) {
        try { return AppType.valueOf(productCode); }
        catch (IllegalArgumentException e) { return AppType.CONTACT_CENTER; }
    }
    // ================================================================
    // PRIVATE HELPERS
    // ================================================================

    private Tenant findTenantOrThrow(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
    }

    private void rollbackKeycloak(Tenant tenant) {
        if (tenant.getKeycloakRealmName() != null) {
            try {
                keycloakRealmService.deleteTenant(tenant.getId());
                tenant.setKeycloakRealmName(null);
                log.info("Rolled back Keycloak realm for tenant {}", tenant.getId());
            } catch (Exception ex) {
                log.warn("Failed to rollback Keycloak for tenant {}: {}",
                        tenant.getId(), ex.getMessage());
            }
        }
    }
}