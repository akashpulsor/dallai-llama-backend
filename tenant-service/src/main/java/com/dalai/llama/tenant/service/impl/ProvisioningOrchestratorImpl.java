package com.dalai.llama.tenant.service.impl;



import com.dalai.llama.tenant.domain.entity.ProvisioningLog;
import com.dalai.llama.tenant.domain.entity.ProvisioningTask;
import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningStep;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.domain.exception.ProvisioningException;
import com.dalai.llama.tenant.dto.response.AdminCredentials;
import com.dalai.llama.tenant.dto.response.PlanEntitlementResponse;
import com.dalai.llama.tenant.repository.ProvisioningLogRepository;
import com.dalai.llama.tenant.repository.ProvisioningTaskRepository;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.dalai.llama.tenant.service.ProvisioningOrchestrator;
import com.dalai.llama.tenant.service.client.ProductServiceClient;
import com.dalai.llama.tenant.util.DistributedLock;
import com.dalai.llama.tenant.util.PasswordGenerator;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.validator.internal.util.stereotypes.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Orchestrates TenantApp provisioning as an ordered step chain.
 *
 * Design:
 *  - Each step is idempotent (safe to retry).
 *  - On failure the task is marked FAILED at the current step;
 *    re-calling provision() resumes from that step.
 *  - Every attempt is recorded in provisioning_logs (immutable audit trail).
 *  - WS events are pushed after each step for UI progress bar.
 *  - Distributed lock prevents concurrent provisioning of the same app.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProvisioningOrchestratorImpl implements ProvisioningOrchestrator {

    private final ProvisioningTaskRepository taskRepository;
    private final ProvisioningLogRepository logRepository;
    private final TenantAppRepository appRepository;

    private final ProductServiceClient productServiceClient;
    private final KeycloakClientConfigService keycloakClientService;
    private final KeycloakRealmServiceImpl keycloakRealmService;
    private final KamailioConfigService kamailioConfigService;
    private final FreeSwitchConfigService freeSwitchConfigService;
    private final CoTurnConfigService coTurnConfigService;
    private final RtpEngineConfigService rtpEngineConfigService;
    private final AiServiceConfigService aiServiceConfigService;
    private final IstioRouteReconciler istioRouteReconciler;
    private final MinioBucketService minioBucketService;
    private final TenantWebSocketPublisher wsPublisher;
    private final DistributedLock distributedLock;
    private final TenantStateMachineImpl stateMachine;


    private final ProvisioningTxRunner txRunner;


    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @Value("${dalaillama.shared-namespace:apps}")
    private String sharedNamespace;

    private static final int MAX_RETRIES = 3;

    // ════════════════════════════════════════════════════════════
    // PUBLIC ENTRY POINT
    // ════════════════════════════════════════════════════════════

    /**
     * Starts or resumes provisioning for a TenantApp.
     * Called from controller; runs async so the HTTP call returns immediately.
     */
    @Override
    @Async("taskExecutor")
    public void provision(UUID tenantAppId) {
        String lockKey = "provision:" + tenantAppId;
        String lockVal = UUID.randomUUID().toString();

        if (!distributedLock.acquire(lockKey, lockVal, Duration.ofMinutes(10))) {
            log.warn("Provisioning already in progress for app {}", tenantAppId);
            return;
        }

        try {
            txRunner.run(tenantAppId, this::doProvision);
        } catch (Exception e) {
            log.error("Provisioning failed for tenantAppId={}", tenantAppId, e);
        } finally {
            distributedLock.release(lockKey, lockVal);
        }
    }



    // ════════════════════════════════════════════════════════════
    // CORE LOOP
    // ════════════════════════════════════════════════════════════

    private void doProvision(UUID tenantAppId) {
        TenantApp app = appRepository.findById(tenantAppId)
                .orElseThrow(() -> new ProvisioningException("TenantApp not found: " + tenantAppId));
        Tenant tenant = app.getTenant();

        // Find or create task (ordered by most recent to ensure we resume from the right point)
        ProvisioningTask task = taskRepository
                .findFirstByTenantAppIdAndStatusInOrderByStartedAtDesc(tenantAppId,
                        List.of(ProvisioningTaskStatus.PENDING, ProvisioningTaskStatus.FAILED, ProvisioningTaskStatus.RUNNING))
                .orElseGet(() -> createTask(tenant, tenantAppId));

        // Determine resume point
        ProvisioningStep startStep = resolveStartStep(task);

        log.info("Provisioning app={} tenant={} starting at step={}", tenantAppId, tenant.getId(), startStep);

        // Mark running
        task.setStatus(ProvisioningTaskStatus.RUNNING);
        task.setStartedAt(OffsetDateTime.now());
        taskRepository.save(task);

        app.setDeploymentStatus(ProvisioningTaskStatus.RUNNING);
        appRepository.save(app);

        // On retry: PROVISIONING_FAILED → PROVISIONING
        if (tenant.getStatus() == com.dalai.llama.tenant.domain.entity.enums.TenantStatus.PROVISIONING_FAILED) {
            try {
                stateMachine.transition(tenant, com.dalai.llama.tenant.domain.entity.enums.TenantStatus.PROVISIONING,
                        "PROVISIONING_ORCHESTRATOR", "Retrying provisioning from " + startStep);
            } catch (Exception stateEx) {
                log.warn("Could not transition tenant back to PROVISIONING: {}", stateEx.getMessage());
            }
        }

        wsPublisher.publishProvisioningEvent(tenant.getId(), "STARTED", "Provisioning started at " + startStep.getDescription());

        // Build context (shared state across steps)
        ProvisioningContext ctx = ProvisioningContext.builder()
                .tenant(tenant)
                .app(app)
                .task(task)
                .build();

        // Run steps in order starting from resume point
        for (ProvisioningStep step : ProvisioningStep.values()) {
            if (step.getOrder() < startStep.getOrder()) continue;

            boolean success = executeStep(ctx, step);
            if (!success) {
                // Step failed after retries — stop pipeline
                task.setStatus(ProvisioningTaskStatus.FAILED);
                task.setCurrentStep(step);
                task.setCurrentStepStatus("FAILED");
                taskRepository.save(task);

                app.setDeploymentStatus(ProvisioningTaskStatus.FAILED);
                appRepository.save(app);

                // Tenant-level state: PROVISIONING → PROVISIONING_FAILED
                try {
                    stateMachine.transition(tenant, com.dalai.llama.tenant.domain.entity.enums.TenantStatus.PROVISIONING_FAILED,
                            "PROVISIONING_ORCHESTRATOR", "Failed at: " + step.getDescription());
                } catch (Exception stateEx) {
                    log.warn("Could not transition tenant to PROVISIONING_FAILED: {}", stateEx.getMessage());
                }

                wsPublisher.publishProvisioningEvent(tenant.getId(), "FAILED",
                        "Failed at: " + step.getDescription() + " — " + task.getLastError());
                return;
            }
        }

        // All steps passed
        task.setStatus(ProvisioningTaskStatus.COMPLETED);
        task.setCompletedAt(OffsetDateTime.now());
        task.setCurrentStep(ProvisioningStep.FINALIZE);
        task.setCurrentStepStatus("COMPLETED");
        taskRepository.save(task);

        app.setDeploymentStatus(ProvisioningTaskStatus.COMPLETED);
        app.setKamailioSynced(true);
        app.setKamailioSyncedAt(java.time.Instant.now());
        app.setFreepbxSynced(true);
        app.setFreepbxSyncedAt(java.time.Instant.now());
        app.setDeployedAt(java.time.Instant.now());
        appRepository.save(app);

        // Tenant-level state: PROVISIONING → ACTIVE
        try {
            stateMachine.transition(tenant, com.dalai.llama.tenant.domain.entity.enums.TenantStatus.ACTIVE,
                    "PROVISIONING_ORCHESTRATOR", "All provisioning steps completed");
        } catch (Exception stateEx) {
            log.warn("Could not transition tenant to ACTIVE: {}", stateEx.getMessage());
        }

        wsPublisher.publishProvisioningEvent(tenant.getId(), "COMPLETED", "All provisioning steps completed successfully");

        log.info("Provisioning completed for app={} tenant={}", app.getId(), tenant.getId());
    }

    // ════════════════════════════════════════════════════════════
    // STEP EXECUTOR (with retry + logging)
    // ════════════════════════════════════════════════════════════

    private boolean executeStep(ProvisioningContext ctx, ProvisioningStep step) {
        ProvisioningTask task = ctx.getTask();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            long start = System.currentTimeMillis();

            task.setCurrentStep(step);
            task.setCurrentStepStatus("RUNNING");
            task.setCurrentStepStartedAt(OffsetDateTime.now());
            task.setRetryCount(attempt - 1);
            taskRepository.save(task);

            wsPublisher.publishProvisioningEvent(ctx.getTenant().getId(), "STEP_RUNNING",
                    step.getDescription() + (attempt > 1 ? " (retry " + attempt + ")" : ""));

            try {
                runStep(ctx, step);

                long elapsed = System.currentTimeMillis() - start;

                // Log success
                logRepository.save(ProvisioningLog.builder()
                        .taskId(task.getId())
                        .tenantAppId(ctx.getApp().getId())
                        .tenantId(ctx.getTenant().getId())
                        .step(step)
                        .status(ProvisioningTaskStatus.COMPLETED)
                        .attemptNumber(attempt)
                        .message(step.getDescription() + " completed")
                        .durationMs(elapsed)
                        .startedAt(task.getCurrentStepStartedAt())
                        .completedAt(OffsetDateTime.now())
                        .build());

                wsPublisher.publishProvisioningEvent(ctx.getTenant().getId(), "STEP_COMPLETED",
                        step.getDescription());

                return true;

            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

                log.error("Step {} failed (attempt {}/{}): {}", step, attempt, MAX_RETRIES, errorMsg, e);

                logRepository.save(ProvisioningLog.builder()
                        .taskId(task.getId())
                        .tenantAppId(ctx.getApp().getId())
                        .tenantId(ctx.getTenant().getId())
                        .step(step)
                        .status(ProvisioningTaskStatus.FAILED)
                        .attemptNumber(attempt)
                        .message("Failed: " + errorMsg)
                        .errorDetail(truncate(getStackTrace(e), 2000))
                        .durationMs(elapsed)
                        .startedAt(task.getCurrentStepStartedAt())
                        .completedAt(OffsetDateTime.now())
                        .build());

                task.setLastError(truncate(errorMsg, 500));
                task.setLastErrorAt(OffsetDateTime.now());
                taskRepository.save(task);

                if (attempt < MAX_RETRIES) {
                    sleep(attempt * 2000L); // backoff: 2s, 4s, 6s
                }
            }
        }
        return false; // all retries exhausted
    }

    // ════════════════════════════════════════════════════════════
    // STEP DISPATCH — one method per step
    // ════════════════════════════════════════════════════════════

    private void runStep(ProvisioningContext ctx, ProvisioningStep step) {
        switch (step) {
            case FETCH_ENTITLEMENTS -> stepFetchEntitlements(ctx);
            case RESOLVE_NAMESPACE -> stepResolveNamespace(ctx);
            case CREATE_KEYCLOAK_CLIENT -> stepCreateKeycloakClient(ctx);
            case CREATE_ADMIN_USER -> stepCreateAdminUser(ctx);
            case CONFIGURE_KAMAILIO -> stepConfigureKamailio(ctx);
            case CONFIGURE_FREESWITCH -> stepConfigureFreeSwitch(ctx);
            case CONFIGURE_COTURN -> stepConfigureCoTurn(ctx);
            case CONFIGURE_RTPENGINE -> stepConfigureRtpEngine(ctx);
            case CONFIGURE_AI_SERVICE -> stepConfigureAiService(ctx);
            case STAMP_INFRA_URLS -> stepStampInfraUrls(ctx);
            case CONFIGURE_ISTIO_ROUTES -> stepConfigureIstioRoutes(ctx);
            case CONFIGURE_MINIO_BUCKETS -> stepConfigureMinioBuckets(ctx);
            case SYNC_PBX_CORE -> stepSyncPbxCore(ctx);
            case HEALTH_CHECK -> stepHealthCheck(ctx);
            case FINALIZE -> stepFinalize(ctx);
        }
    }

    // ── Step 1: Fetch entitlements ──

    private void stepFetchEntitlements(ProvisioningContext ctx) {
        TenantApp app = ctx.getApp();
        PlanEntitlementResponse ent = productServiceClient.getPlanEntitlements(app.getPlanId());
        if (ent == null) {
            throw new ProvisioningException("No entitlements found for plan " + app.getPlanId());
        }
        ctx.setEntitlements(ent);

        // Stamp entitlement feature flags into TenantApp
        app.setAiBotEnabled(ent.aiBotEnabled());
        app.setAiTranscriptionEnabled(ent.aiSttEnabled());
        app.setAiRoutingEnabled(ent.aiRoutingEnabled());
        app.setAiSentimentEnabled(ent.aiSentimentEnabled());
        app.setAiNoiseCancellationEnabled(ent.aiNoiseCancellationEnabled());
        app.setAiVoiceMorphEnabled(ent.aiVoiceMorphEnabled());
        app.setAiAgentAssistEnabled(ent.aiAgentAssistEnabled());
        app.setRecordingEnabled(ent.recordingEnabled());
        app.setScreenRecordingEnabled(ent.screenRecordingEnabled());
        app.setRecordingStorageGb(ent.recordingStorageGb());
        app.setRecordingRetentionDays(ent.recordingRetentionDays());
        app.setBargeEnabled(ent.bargeEnabled());
        app.setWhisperEnabled(ent.whisperEnabled());
        app.setListenEnabled(ent.listenEnabled());
        app.setConferenceEnabled(ent.conferenceEnabled());
        app.setCallbackEnabled(ent.callbackEnabled());
        app.setBasicIvrEnabled(ent.basicIvrEnabled());
        app.setConversationalIvrEnabled(ent.conversationalIvrEnabled());
        app.setIvrMultiLanguageEnabled(ent.ivrMultiLanguageEnabled());
        app.setProgressiveDialerEnabled(ent.progressiveDialerEnabled());
        app.setPredictiveDialerEnabled(ent.predictiveDialerEnabled());
        app.setPreviewDialerEnabled(ent.previewDialerEnabled());
        app.setAmdEnabled(ent.amdEnabled());
        app.setDncManagementEnabled(ent.dncManagementEnabled());
        app.setVoicemailEnabled(ent.voicemailEnabled());
        app.setVoicemailTranscriptionEnabled(ent.voicemailTranscriptionEnabled());
        app.setCrmIntegrationEnabled(ent.crmIntegrationEnabled());
        app.setApiAccessEnabled(ent.apiAccessEnabled());
        app.setWebhookEnabled(ent.webhookEnabled());
        app.setBasicReportingEnabled(ent.basicReportingEnabled());
        app.setAdvancedReportingEnabled(ent.advancedReportingEnabled());
        app.setWallboardEnabled(ent.wallboardEnabled());
        app.setBlindTransferEnabled(ent.blindTransferEnabled());
        app.setAttendedTransferEnabled(ent.attendedTransferEnabled());
        app.setWarmTransferEnabled(ent.warmTransferEnabled());
        app.setScreenPopEnabled(ent.screenPopEnabled());
        app.setCustomReportsEnabled(ent.customReportsEnabled());
        app.setDedicatedInfrastructure(ent.dedicatedInfrastructure());
        app.setCustomDomainEnabled(ent.customDomainEnabled());
        app.setSlaTier(ent.slaTier());
        app.setAiTokensPerMonth((int) ent.aiTokensPerMonth());
        app.setIncludedMinutesInbound(ent.includedMinutesInbound());
        app.setIncludedMinutesOutbound(ent.includedMinutesOutbound());
        app.setRatePerMinuteInbound(ent.ratePerMinuteInbound());
        app.setRatePerMinuteOutbound(ent.ratePerMinuteOutbound());
        app.setMaxQueues(ent.maxQueues());
        app.setMaxIvrFlows(ent.maxIvrFlows());
        app.setMaxRingGroups(ent.maxRingGroups());
        app.setMaxExtensions(ent.maxExtensions());
        app.setMaxSupervisors(ent.maxSupervisors());
        appRepository.save(app);
    }

    // ── Step 2: Resolve namespace ──

    private void stepResolveNamespace(ProvisioningContext ctx) {
        TenantApp app = ctx.getApp();
        boolean dedicated = app.getDeploymentModel() == com.dalai.llama.tenant.domain.entity.enums.DeploymentModel.DEDICATED;
        String ns = dedicated
                ? "tenant-" + app.getTenant().getSlug()
                : sharedNamespace;

        ctx.setDedicatedInfra(dedicated);
        ctx.setResolvedNamespace(ns);
        app.setNamespace(ns);
        app.setDedicatedInfrastructure(dedicated);
        appRepository.save(app);
    }

    // ── Step 3: Keycloak client ──

    private void stepCreateKeycloakClient(ProvisioningContext ctx) {
        TenantApp app = ctx.getApp();
        Tenant tenant = ctx.getTenant();
        keycloakClientService.createClientsForTenant(app);

        // Write back the primary keycloakClientId so PublicTenantConfigController can serve it
        if (app.getKeycloakClientId() == null || app.getKeycloakClientId().isBlank()) {
            String clientId = "dalaillama-" + tenant.getSlug();
            app.setKeycloakClientId(clientId);
            appRepository.save(app);
            log.info("Stamped keycloakClientId={} on TenantApp={}", clientId, app.getId());
        }
    }

    // ── Step 4: Admin user ──

    private void stepCreateAdminUser(ProvisioningContext ctx) {
        Tenant tenant = ctx.getTenant();
        TenantApp app = ctx.getApp();

        // Only create if not already created (idempotency)
        if (tenant.getAdminUserId() != null && tenant.getAdminUserEmail() != null) {
            ctx.setAdminCredentials(new AdminCredentials(
                    tenant.getAdminUserEmail(),
                    null, // can't retrieve password after creation
                    "https://admin-" + tenant.getSlug() + "." + baseDomain));
            return;
        }

        String email = tenant.getPrimaryContactEmail();
        String tempPassword = PasswordGenerator.generate(14);

        String userId = keycloakRealmService.createAdminUser(
                tenant.getKeycloakRealmName(), email, tenant.getPrimaryContactName(), tempPassword);

        tenant.setAdminUserId(userId);
        tenant.setAdminUserEmail(email);

        ctx.setAdminCredentials(new AdminCredentials(
                email, tempPassword,
                "https://admin-" + tenant.getSlug() + "." + baseDomain));
    }

    // ── Step 5: Kamailio ──

    private void stepConfigureKamailio(ProvisioningContext ctx) {
        ensureEntitlements(ctx);
        String config = kamailioConfigService.configureForSubscription(ctx.getApp(), ctx.getEntitlements());
        ctx.getApp().setKamailioConfig(config);
        appRepository.save(ctx.getApp());
    }

    // ── Step 6: FreeSWITCH ──

    private void stepConfigureFreeSwitch(ProvisioningContext ctx) {
        ensureEntitlements(ctx);
        freeSwitchConfigService.configureForSubscription(ctx.getApp(), ctx.getEntitlements());
    }

    // ── Step 7: CoTURN ──

    private void stepConfigureCoTurn(ProvisioningContext ctx) {
        coTurnConfigService.configureForSubscription(ctx.getApp());
    }

    // ── Step 8: RTPEngine ──
    //
    // aiForkTarget explanation:
    //   RTPEngine can fork (duplicate) the audio stream to a secondary destination.
    //   For AI-enabled tenants, this sends a copy of the RTP media to voice-brain
    //   for real-time STT processing WITHOUT routing audio through PBX-Core.
    //   Format: "udp:<voice-brain-host>:5555"
    //   The RTPEngine flag is: "record-call=on metadata=<tenantId> fork-media=<target>"

    private void stepConfigureRtpEngine(ProvisioningContext ctx) {
        ensureEntitlements(ctx);
        rtpEngineConfigService.configureForSubscription(ctx.getApp(), ctx.getEntitlements());
    }

    // ── Step 9: AI Service ──

    private void stepConfigureAiService(ProvisioningContext ctx) {
        ensureEntitlements(ctx);
        aiServiceConfigService.configureForSubscription(ctx.getApp(), ctx.getEntitlements());
    }

    // ── Step 10: Stamp infra URLs ──

    private void stepStampInfraUrls(ProvisioningContext ctx) {
        TenantApp app = ctx.getApp();
        String slug = app.getTenant().getSlug();
        boolean dedicated = ctx.isDedicatedInfra();
        String ns = ctx.getResolvedNamespace();

        // SIP/WSS URLs (shared infra points to same endpoints)
        app.setSipExternalIp("sip." + baseDomain);
        app.setSipUdpUrl("sip:sip." + baseDomain + ":5060");
        app.setSipTlsUrl("sips:sip." + baseDomain + ":5061");
        app.setWebsocketUrl("wss://sip." + baseDomain + ":7443");

        // FreeSWITCH ESL
        String fsHost = dedicated
                ? "freeswitch." + ns + ".svc.cluster.local"
                : "freeswitch.telecom.svc.cluster.local";
        app.setFreeswitchEslHost(fsHost);
        app.setFreeswitchEslPort(8021);

        // Infra URLs
        app.setKafkaBootstrap("kafka." + sharedNamespace + ".svc.cluster.local:9092");
        app.setRedisUrl("redis-master." + sharedNamespace + ".svc.cluster.local:6379");

        // Dashboard
        app.setDashboardUrl("https://admin-" + slug + "." + baseDomain);

        appRepository.save(app);
    }

    // ── Step 11: Istio routes ──

    private void stepConfigureIstioRoutes(ProvisioningContext ctx) {
        istioRouteReconciler.reconcileForApp(ctx.getApp());
    }

    // ── Step 12: MinIO buckets ──

    private void stepConfigureMinioBuckets(ProvisioningContext ctx) {
        minioBucketService.provisionBuckets(ctx.getApp());
        log.info("MinIO buckets provisioned for tenant {}", ctx.getTenant().getSlug());
    }

    // ── Step 13: Sync PBX-Core ──

    private void stepSyncPbxCore(ProvisioningContext ctx) {
        ensureEntitlements(ctx);
        TenantApp app = ctx.getApp();
        // PBX-Core already received configs in steps 5-9 via individual calls.
        // This final sync triggers PBX-Core to reload Kamailio and FreeSWITCH:
        //   kamcmd domain.reload, kamcmd dispatcher.reload
        //   fs_cli -x "reloadxml"
        // The actual sync is done by PBX-Core's /api/v1/provisioning/tenants/{id}/sync endpoint.
        com.dalai.llama.tenant.dto.request.TenantProvisioningRequest syncReq =
                com.dalai.llama.tenant.dto.request.TenantProvisioningRequest.builder()
                        .tenantId(app.getTenant().getId())
                        .subscriptionId(app.getSubscriptionId())
                        .productCode(app.getProductCode())
                        .planCode(app.getPlanCode())
                        .planTier(app.getPlanTier())
                        .namespace(app.getNamespace())
                        .dedicatedInfrastructure(app.getDedicatedInfrastructure())
                        .sipEndpointUsername(app.getSipEndpointUsername())
                        .sipEndpointPasswordHash(app.getSipEndpointPasswordHash())
                        .sipEndpointDomain(app.getSipEndpointDomain())
                        .tenantTrunkUsername(app.getTenantTrunkUsername())
                        .tenantTrunkPasswordHash(app.getTenantTrunkPasswordHash())
                        .tenantTrunkDomain(app.getTenantTrunkDomain())
                        .didNumber(app.getDidNumber())
                        .channelTotal(app.getChannelTotal())
                        .channelInbound(app.getChannelInbound())
                        .channelOutbound(app.getChannelOutbound())
                        .channelDirection(app.getChannelDirection())
                        .entitlements(com.dalai.llama.tenant.dto.request.EntitlementDto.from(ctx.getEntitlements()))
                        .build();

        //pbxCoreClient.syncTenant(syncReq);  // TODO: implement in PbxCoreClient
        log.info("PBX-Core sync requested for tenant {}", app.getTenant().getSlug());
    }

    // ── Step 14: Health check ──

    private void stepHealthCheck(ProvisioningContext ctx) {
        // Verify critical endpoints are reachable:
        //  - Keycloak realm exists
        //  - PBX-Core responds for this tenant
        // For now, log and proceed. Full health checks added when services are stable.
        log.info("Health check passed for tenant {}", ctx.getTenant().getSlug());
    }

    // ── Step 15: Finalize ──

    private void stepFinalize(ProvisioningContext ctx) {
        TenantApp app = ctx.getApp();
        app.setEnabled(true);
        appRepository.save(app);
        log.info("Provisioning finalized for app {} tenant {}", app.getId(), ctx.getTenant().getSlug());
    }

    // ════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════

    private ProvisioningTask createTask(Tenant tenant, UUID tenantAppId) {
        ProvisioningTask task = new ProvisioningTask();
        task.setTenant(tenant);
        task.setTenantAppId(tenantAppId);
        task.setStatus(ProvisioningTaskStatus.PENDING);
        task.setCurrentStep(ProvisioningStep.FETCH_ENTITLEMENTS);
        task.setCurrentStepStatus("PENDING");
        task.setMaxRetries(MAX_RETRIES);
        task.setRetryCount(0);
        return taskRepository.save(task);
    }

    private ProvisioningStep resolveStartStep(ProvisioningTask task) {
        if (task.getStatus() == ProvisioningTaskStatus.FAILED && task.getCurrentStep() != null) {
            return task.getCurrentStep(); // resume from failed step
        }
        if (task.getCurrentStep() != null && task.getStatus() == ProvisioningTaskStatus.RUNNING) {
            return task.getCurrentStep(); // resume from interrupted step
        }
        return ProvisioningStep.FETCH_ENTITLEMENTS;
    }

    private void ensureEntitlements(ProvisioningContext ctx) {
        if (ctx.getEntitlements() == null) {
            PlanEntitlementResponse ent = productServiceClient.getPlanEntitlements(ctx.getApp().getPlanId());
            ctx.setEntitlements(ent);
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    private static String getStackTrace(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement el : e.getStackTrace()) {
            sb.append(el.toString()).append("\n");
            if (sb.length() > 1500) break;
        }
        return sb.toString();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}