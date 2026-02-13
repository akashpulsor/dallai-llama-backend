package com.dalai.llama.tenant.service.provisioning.telecom;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.DeploymentModel;
import com.dalai.llama.tenant.domain.exception.ProvisioningException;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.service.provisioning.telecom.config.*;
import com.dalai.llama.tenant.service.provisioning.telecom.k8s.TelecomK8sDeployer;
import com.dalai.llama.tenant.service.provisioning.telecom.model.CCBuilderConfig;
import com.dalai.llama.tenant.service.provisioning.telecom.model.TelecomStackConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the complete telecom stack deployment for a tenant.
 *
 * Deployment Order:
 * 1. Generate all configs (Kamailio, FreeSWITCH, RTPEngine, CoTURN)
 * 2. Create ConfigMaps in K8s
 * 3. Deploy StatefulSets/Deployments
 * 4. Create Services (ClusterIP + LoadBalancer)
 * 5. Wait for LoadBalancer IP
 * 6. Update tenant with endpoints
 * 7. Health check all components
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelecomStackProvisioner {

    private final TenantRepository tenantRepository;
    private final KubernetesClient k8sClient;

    // Config Builders
    private final KamailioConfigBuilder kamailioConfigBuilder;
    private final FreeSwitchConfigBuilder freeSwitchConfigBuilder;
    private final RtpEngineConfigBuilder rtpEngineConfigBuilder;
    private final CoturnConfigBuilder coturnConfigBuilder;

    // K8s Deployer
    private final TelecomK8sDeployer k8sDeployer;

    @Value("${provisioning.telecom.ip-wait-timeout-minutes:10}")
    private int ipWaitTimeoutMinutes;

    @Value("${provisioning.telecom.health-check-timeout-seconds:60}")
    private int healthCheckTimeoutSeconds;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    /**
     * Deploy the complete telecom stack for a tenant.
     * Returns the external SIP IP once available.
     */
    public String deployTelecomStack(UUID tenantId) {
     /*   Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ProvisioningException("Tenant not found: " + tenantId));

        String namespace = tenant.getNamespace();
        if (namespace == null || namespace.isBlank()) {
            throw new ProvisioningException("Tenant namespace not set");
        }

        log.info("Deploying telecom stack for tenant {} in namespace {}", tenantId, namespace);

        try {
            // 1. Build telecom stack configuration
            TelecomStackConfig stackConfig = buildStackConfig(tenant);

            // 2. Generate all component configs
            String kamailioConfig = kamailioConfigBuilder.build(tenant, stackConfig);
            String freeSwitchDialplan = freeSwitchConfigBuilder.buildDialplan(tenant, stackConfig);
            String freeSwitchDirectory = freeSwitchConfigBuilder.buildDirectory(tenant, stackConfig);
            String freeSwitchSofiaConfig = freeSwitchConfigBuilder.buildSofiaConfig(tenant, stackConfig);
            String freeSwitchEventSocket = freeSwitchConfigBuilder.buildEventSocketConfig(tenant, stackConfig);
            String rtpEngineConfig = rtpEngineConfigBuilder.build(tenant, stackConfig);
            String coturnConfig = coturnConfigBuilder.build(tenant, stackConfig);

            // 3. Create ConfigMaps
            Map<String, String> kamailioFiles = Map.of(
                    "kamailio.cfg", kamailioConfig,
                    "dispatcher.list", buildDispatcherList(tenant)
            );

            Map<String, String> freeSwitchFiles = Map.of(
                    "dialplan.xml", freeSwitchDialplan,
                    "directory.xml", freeSwitchDirectory,
                    "sofia.conf.xml", freeSwitchSofiaConfig,
                    "event_socket.conf.xml", freeSwitchEventSocket,
                    "ivr_handler.lua", freeSwitchConfigBuilder.buildIvrHandlerLua(tenant, stackConfig)
            );

            Map<String, String> rtpEngineFiles = Map.of(
                    "rtpengine.conf", rtpEngineConfig
            );

            Map<String, String> coturnFiles = Map.of(
                    "turnserver.conf", coturnConfig
            );

            k8sDeployer.createConfigMaps(namespace, tenant.getSlug(),
                    kamailioFiles, freeSwitchFiles, rtpEngineFiles, coturnFiles);

            // 4. Deploy all components
            k8sDeployer.deployRtpEngine(namespace, tenant);
            k8sDeployer.deployCoturn(namespace, tenant);
            k8sDeployer.deployFreeSWITCH(namespace, tenant);
            k8sDeployer.deployKamailio(namespace, tenant);

            // 5. Create Services
            k8sDeployer.createServices(namespace, tenant);

            // 6. Wait for LoadBalancer IP
            String externalIp = waitForExternalIp(namespace, tenant.getSlug());

            // 7. Update tenant with endpoints
            updateTenantEndpoints(tenant, externalIp, stackConfig);

            // 8. Health check
            performHealthCheck(namespace, tenant);

            log.info("Telecom stack deployed successfully for tenant {}. External IP: {}",
                    tenantId, externalIp);

            return externalIp;

        } catch (Exception e) {
            log.error("Failed to deploy telecom stack for tenant {}: {}", tenantId, e.getMessage(), e);
            throw new ProvisioningException("Telecom stack deployment failed: " + e.getMessage(), e);
        }

      */
        return null;
    }

    /**
     * Deploy shared telecom stack (called once for all SHARED tenants)
     */
    public String deploySharedTelecomStack(String namespace) {
        log.info("Deploying SHARED telecom stack in namespace {}", namespace);

        try {
            // 1. Ensure namespace exists
            if (k8sClient.namespaces().withName(namespace).get() == null) {
                log.info("Creating shared namespace: {}", namespace);
                k8sClient.namespaces().resource(
                        new io.fabric8.kubernetes.api.model.NamespaceBuilder()
                                .withNewMetadata()
                                .withName(namespace)
                                .withLabels(Map.of(
                                        "type", "shared-telecom",
                                        "managed-by", "dalai-llama"
                                ))
                                .endMetadata()
                                .build()
                ).create();
            }

            // 2. Build shared stack config
            TelecomStackConfig stackConfig = TelecomStackConfig.defaultConfig("shared", "shared")
                    .namespace(namespace)
                    .realm("shared." + baseDomain)
                    .isDedicated(false)
                    .pbxCoreUrl("http://pbx-core.platform.svc.cluster.local:8080")
                    .agentServiceUrl("http://agent-service.platform.svc.cluster.local:8080")
                    .aiServiceUrl("ws://ai-service.platform.svc.cluster.local:9000")
                    .kafkaBootstrap("kafka.platform.svc.cluster.local:9092")
                    .kafkaTopicPrefix("shared")
                    .redisUrl("redis://redis.platform.svc.cluster.local:6379")
                    .eslPassword("ClueCon")  // Default shared ESL password
                    .maxAgents(1000)
                    .maxConcurrentCalls(500)
                    .maxQueues(100)
                    .enableConference(true)
                    .maxConferenceRooms(100)
                    .maxConferenceParticipants(50)
                    .enableSupervisorFeatures(true)
                    .enableSilentMonitor(true)
                    .enableWhisper(true)
                    .enableBarge(true)
                    .build();

            // 3. Generate all component configs
            String kamailioConfig = kamailioConfigBuilder.build(null, stackConfig);
            String freeSwitchDialplan = freeSwitchConfigBuilder.buildDialplan(null, stackConfig);
            String freeSwitchDirectory = freeSwitchConfigBuilder.buildDirectory(null, stackConfig);
            String freeSwitchSofiaConfig = freeSwitchConfigBuilder.buildSofiaConfig(null, stackConfig);
            String freeSwitchEventSocket = freeSwitchConfigBuilder.buildEventSocketConfig(null, stackConfig);
            String freeSwitchIvrHandler = freeSwitchConfigBuilder.buildIvrHandlerLua(null, stackConfig);
            String rtpEngineConfig = rtpEngineConfigBuilder.build(null, stackConfig);
            String coturnConfig = coturnConfigBuilder.build(null, stackConfig);

            // 4. Create ConfigMaps
            Map<String, String> kamailioFiles = Map.of(
                    "kamailio.cfg", kamailioConfig,
                    "dispatcher.list", "# Shared dispatcher list\n"
            );

            Map<String, String> freeSwitchFiles = Map.of(
                    "dialplan.xml", freeSwitchDialplan,
                    "directory.xml", freeSwitchDirectory,
                    "sofia.conf.xml", freeSwitchSofiaConfig,
                    "event_socket.conf.xml", freeSwitchEventSocket,
                    "ivr_handler.lua", freeSwitchIvrHandler
            );

            Map<String, String> rtpEngineFiles = Map.of("rtpengine.conf", rtpEngineConfig);
            Map<String, String> coturnFiles = Map.of("turnserver.conf", coturnConfig);

            k8sDeployer.createConfigMaps(namespace, "shared",
                    kamailioFiles, freeSwitchFiles, rtpEngineFiles, coturnFiles);

            // 5. Deploy all components (pass null for tenant - shared mode)
            k8sDeployer.deployRtpEngine(namespace, null);
            k8sDeployer.deployCoturn(namespace, null);
            k8sDeployer.deployFreeSWITCH(namespace, null);
            k8sDeployer.deployKamailio(namespace, null);

            // 6. Create Services
            k8sDeployer.createServices(namespace, null);

            // 7. Wait for LoadBalancer IP
            String externalIp = waitForExternalIp(namespace, "shared");

            log.info("Shared telecom stack deployed successfully. External IP: {}", externalIp);

            return externalIp;

        } catch (Exception e) {
            log.error("Failed to deploy shared telecom stack: {}", e.getMessage(), e);
            throw new ProvisioningException("Shared telecom stack deployment failed: " + e.getMessage(), e);
        }
    }

    /**
     * Build the telecom stack configuration based on tenant settings.
     * Uses CCBuilderConfig from wizard if available, otherwise falls back to defaults.
     */
    private TelecomStackConfig buildStackConfig(TenantApp tenantApp) {
        /*
        // If ccBuilderConfig exists (from CC Builder wizard), use it
        if (tenantApp.getCcBuilderConfig() != null && !tenantApp.getCcBuilderConfig().isBlank()) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                CCBuilderConfig ccConfig = mapper.readValue(tenantApp.getCcBuilderConfig(), CCBuilderConfig.class);

                TelecomStackConfig config = ccConfig.toTelecomStackConfig(
                        tenantApp.getId().toString(),
                        tenantApp.getSlug(),
                        "http://pbx-core." + tenantApp.getNamespace() + ".svc.cluster.local:8080",
                        "ws://ai-service." + tenantApp.getNamespace() + ".svc.cluster.local:9000",
                        resolveKafkaBootstrap(tenantApp),
                        resolveRedisUrl(tenantApp)
                );

                // Override ESL password (generated per-tenant)
                return TelecomStackConfig.builder()
                        // Copy all from ccConfig conversion
                        .tenantId(config.getTenantId())
                        .tenantSlug(config.getTenantSlug())
                        .namespace(tenant.getNamespace())
                        .realm(tenant.getSlug() + "." + baseDomain)
                        .isDedicated(config.isDedicated())
                        .pbxCoreUrl(config.getPbxCoreUrl())
                        .agentServiceUrl("http://agent-service." + tenant.getNamespace() + ".svc.cluster.local:8080")
                        .aiServiceUrl(config.getAiServiceUrl())
                        .kafkaBootstrap(config.getKafkaBootstrap())
                        .kafkaTopicPrefix(config.getKafkaTopicPrefix())
                        .redisUrl(config.getRedisUrl())
                        .enableWebRtc(config.isEnableWebRtc())
                        .wssPort(config.getWssPort())
                        .turnPort(config.getTurnPort())
                        .turnTlsPort(config.getTurnTlsPort())
                        .rtpPortRangeStart(config.getRtpPortRangeStart())
                        .rtpPortRangeEnd(config.getRtpPortRangeEnd())
                        .sipUdpPort(config.getSipUdpPort())
                        .sipTcpPort(config.getSipTcpPort())
                        .sipTlsPort(config.getSipTlsPort())
                        .eslPort(config.getEslPort())
                        .eslPassword(generateEslPassword(tenant))
                        // Capacity
                        .maxAgents(config.getMaxAgents())
                        .maxConcurrentCalls(config.getMaxConcurrentCalls())
                        .maxQueues(config.getMaxQueues())
                        .maxIvrFlows(config.getMaxIvrFlows())
                        .maxDids(config.getMaxDids())
                        // Recording
                        .enableRecording(config.isEnableRecording())
                        .recordingPath("/var/lib/freeswitch/recordings/" + tenant.getSlug())
                        .recordingFormat(config.getRecordingFormat())
                        .recordingStereo(config.isRecordingStereo())
                        .recordingPciPause(config.isRecordingPciPause())
                        .recordingRetentionDays(config.getRecordingRetentionDays())
                        // AI
                        .enableAiTranscription(config.isEnableAiTranscription())
                        .enableAiRouting(config.isEnableAiRouting())
                        .enableAiNoiseCancellation(config.isEnableAiNoiseCancellation())
                        .enableAiSentiment(config.isEnableAiSentiment())
                        .enableAiAgentAssist(config.isEnableAiAgentAssist())
                        .enableAcwAutomation(config.isEnableAcwAutomation())
                        .aiMinutesPerMonth(config.getAiMinutesPerMonth())
                        // Conference
                        .enableConference(config.isEnableConference())
                        .maxConferenceRooms(config.getMaxConferenceRooms())
                        .maxConferenceParticipants(config.getMaxConferenceParticipants())
                        .conferenceRecording(config.isConferenceRecording())
                        .conferenceDialOut(config.isConferenceDialOut())
                        .conferenceModerator(config.isConferenceModerator())
                        .conferenceDefaultProfile(config.getConferenceDefaultProfile())
                        // Supervisor
                        .enableSupervisorFeatures(config.isEnableSupervisorFeatures())
                        .enableSilentMonitor(config.isEnableSilentMonitor())
                        .enableWhisper(config.isEnableWhisper())
                        .enableBarge(config.isEnableBarge())
                        .enableTakeover(config.isEnableTakeover())
                        .enableCoach(config.isEnableCoach())
                        // Queues
                        .enableQueues(config.isEnableQueues())
                        .enableSkillsRouting(config.isEnableSkillsRouting())
                        .enablePriorityRouting(config.isEnablePriorityRouting())
                        .enableCallbackQueue(config.isEnableCallbackQueue())
                        .queueMaxWaitTime(config.getQueueMaxWaitTime())
                        .queueServiceLevel(config.getQueueServiceLevel())
                        .queueOverflowAction(config.getQueueOverflowAction())
                        // Dialer
                        .enableOutboundDialer(config.isEnableOutboundDialer())
                        .enablePredictiveDialing(config.isEnablePredictiveDialing())
                        .enableProgressiveDialing(config.isEnableProgressiveDialing())
                        .enablePreviewDialing(config.isEnablePreviewDialing())
                        .dialerMaxConcurrent(config.getDialerMaxConcurrent())
                        .dialerPacingRatio(config.getDialerPacingRatio())
                        // IVR
                        .enableIvr(config.isEnableIvr())
                        .enableConversationalIvr(config.isEnableConversationalIvr())
                        .enableDtmfFallback(config.isEnableDtmfFallback())
                        .ivrDefaultLanguage(config.getIvrDefaultLanguage())
                        // Voicemail
                        .enableVoicemail(config.isEnableVoicemail())
                        .voicemailMaxDuration(config.getVoicemailMaxDuration())
                        .voicemailTranscription(config.isVoicemailTranscription())
                        .voicemailEmailNotify(config.isVoicemailEmailNotify())
                        // Screen Pop
                        .enableScreenPop(config.isEnableScreenPop())
                        .crmIntegrationType(config.getCrmIntegrationType())
                        // Omnichannel
                        .enableOmnichannel(config.isEnableOmnichannel())
                        .enableWhatsApp(config.isEnableWhatsApp())
                        .enableSms(config.isEnableSms())
                        .enableEmail(config.isEnableEmail())
                        .enableWebChat(config.isEnableWebChat())
                        // Compliance
                        .enableDncList(config.isEnableDncList())
                        .enableCallDisposition(config.isEnableCallDisposition())
                        .enableAgentScripts(config.isEnableAgentScripts())
                        .complianceRegion(config.getComplianceRegion())
                        .build();

            } catch (Exception e) {
                log.warn("Failed to parse ccBuilderConfig for tenant {}, using defaults: {}",
                        tenant.getSlug(), e.getMessage());
            }
        }

        // Fallback: use defaults when ccBuilderConfig not available
        return buildDefaultStackConfig(tenant);

         */
        return null;
    }

    /**
     * Build default config when ccBuilderConfig is not available
     */
    private TelecomStackConfig buildDefaultStackConfig(Tenant tenant) {
        /*
        boolean isDedicated = tenant.getDeploymentModel() == DeploymentModel.DEDICATED;

        return TelecomStackConfig.builder()
                .tenantId(tenant.getId().toString())
                .tenantSlug(tenant.getSlug())
                .namespace(tenant.getNamespace())
                .realm(tenant.getSlug() + "." + baseDomain)
                .isDedicated(isDedicated)
                .pbxCoreUrl("http://pbx-core." + tenant.getNamespace() + ".svc.cluster.local:8080")
                .agentServiceUrl("http://agent-service." + tenant.getNamespace() + ".svc.cluster.local:8080")
                .aiServiceUrl("ws://ai-service." + tenant.getNamespace() + ".svc.cluster.local:9000")
                .kafkaBootstrap(resolveKafkaBootstrap(tenant))
                .kafkaTopicPrefix(tenant.getSlug())
                .redisUrl(resolveRedisUrl(tenant))
                .enableWebRtc(true)
                .wssPort(8089)
                .turnPort(3478)
                .turnTlsPort(5349)
                .rtpPortRangeStart(10000)
                .rtpPortRangeEnd(20000)
                .sipUdpPort(5060)
                .sipTcpPort(5060)
                .sipTlsPort(5061)
                .eslPort(8021)
                .eslPassword(generateEslPassword(tenant))
                .enableAiTranscription(tenant.isAiTranscriptionEnabled())
                .enableAiRouting(tenant.isAiRoutingEnabled())
                .enableAiNoiseCancellation(tenant.isAiNoiseCancellationEnabled())
                .enableRecording(true)
                .recordingPath("/var/lib/freeswitch/recordings/" + tenant.getSlug())
                .recordingFormat("wav")
                .recordingStereo(true)
                .enableQueues(true)
                .enableIvr(true)
                .enableVoicemail(true)
                .voicemailMaxDuration(180)
                .voicemailEmailNotify(true)
                .complianceRegion("IN")
                .build();

         */
        return null;
    }

    private String resolveKafkaBootstrap(TenantApp tenantApp) {
        if (tenantApp.getKafkaBootstrap() != null) {
            return tenantApp.getKafkaBootstrap();
        }
        return tenantApp.isDedicated()
                ? "kafka." + tenantApp.getNamespace() + ".svc.cluster.local:9092"
                : "kafka.platform.svc.cluster.local:9092";
    }

    private String resolveRedisUrl(TenantApp tenantApp) {
        if (tenantApp.getRedisUrl() != null) {
            return tenantApp.getRedisUrl();
        }
        return tenantApp.isDedicated()
                ? "redis://" + tenantApp.getNamespace() + "-redis:6379"
                : "redis://platform-redis:6379";
    }
    /**
     * Build dispatcher list for trunk failover
     */
    private String buildDispatcherList(Tenant tenant) {
        /*
        // For now, return empty dispatcher - will be populated when trunks are configured
        StringBuilder sb = new StringBuilder();
        sb.append("# Dispatcher list for tenant ").append(tenant.getSlug()).append("\n");
        sb.append("# Format: setid destination flags priority attrs\n");
        sb.append("# Trunks will be added via API\n");

        if (tenant.getDidwwTrunkId() != null) {
            // Primary DIDWW trunk
            sb.append("1 sip:sip.didww.com:5060 0 0\n");
        }

        return sb.toString();*/
        return null;
    }

    /**
     * Wait for the LoadBalancer to get an external IP
     */
    private String waitForExternalIp(String namespace, String tenantSlug) {
        String serviceName = "kamailio-sip-lb";
        Instant deadline = Instant.now().plus(Duration.ofMinutes(ipWaitTimeoutMinutes));

        log.info("Waiting for external IP for service {}/{}", namespace, serviceName);

        while (Instant.now().isBefore(deadline)) {
            try {
                var service = k8sClient.services()
                        .inNamespace(namespace)
                        .withName(serviceName)
                        .get();

                if (service != null && service.getStatus() != null
                        && service.getStatus().getLoadBalancer() != null
                        && service.getStatus().getLoadBalancer().getIngress() != null
                        && !service.getStatus().getLoadBalancer().getIngress().isEmpty()) {

                    var ingress = service.getStatus().getLoadBalancer().getIngress().get(0);
                    String ip = ingress.getIp() != null ? ingress.getIp() : ingress.getHostname();

                    if (ip != null && !ip.isEmpty()) {
                        log.info("External IP assigned: {}", ip);
                        return ip;
                    }
                }

                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProvisioningException("Interrupted while waiting for external IP");
            }
        }

        throw new ProvisioningException("Timeout waiting for external IP");
    }

    /**
     * Update tenant entity with all telecom endpoints
     */
    private void updateTenantEndpoints(Tenant tenant, String externalIp, TelecomStackConfig config) {
        /*
        tenant.setSipExternalIp(externalIp);
        tenant.setSipUdpUrl("sip:" + externalIp + ":" + config.getSipUdpPort());
        tenant.setSipTlsUrl("sips:" + externalIp + ":" + config.getSipTlsPort());
        tenant.setTurnUrl("turn:" + externalIp + ":" + config.getTurnPort());
        tenant.setWebsocketUrl("wss://" + tenant.getSlug() + ".wss." + baseDomain);
        tenant.setRtpengineSock("udp://rtpengine." + tenant.getNamespace() + ".svc.cluster.local:22222");
        tenant.setKafkaBootstrap(config.getKafkaBootstrap());
        tenant.setRedisUrl(config.getRedisUrl());

        // FreeSWITCH ESL endpoint for PBX-Core
        tenant.setFreeswitchEslHost("freeswitch." + tenant.getNamespace() + ".svc.cluster.local");
        tenant.setFreeswitchEslPort(config.getEslPort());
        tenant.setFreeswitchEslPassword(config.getEslPassword());

        tenantRepository.save(tenant);

         */
    }

    /**
     * Perform health checks on all components
     */
    private void performHealthCheck(String namespace, Tenant tenant) {
        log.info("Performing health checks for telecom stack in namespace {}", namespace);

        // Check Kamailio health
        if (!checkKamailioHealth(namespace)) {
            throw new ProvisioningException("Kamailio health check failed");
        }

        // Check FreeSWITCH health
        if (!checkFreeSwitchHealth(namespace)) {
            throw new ProvisioningException("FreeSWITCH health check failed");
        }

        // Check RTPEngine health
        if (!checkRtpEngineHealth(namespace)) {
            throw new ProvisioningException("RTPEngine health check failed");
        }

        // Check CoTURN health
        if (!checkCoturnHealth(namespace)) {
            throw new ProvisioningException("CoTURN health check failed");
        }

        log.info("All health checks passed for namespace {}", namespace);
    }

    private boolean checkKamailioHealth(String namespace) {
        // TODO: Implement actual health check via kamctl or HTTP module
        return checkPodReady(namespace, "kamailio");
    }

    private boolean checkFreeSwitchHealth(String namespace) {
        // TODO: Implement actual health check via ESL
        return checkPodReady(namespace, "freeswitch");
    }

    private boolean checkRtpEngineHealth(String namespace) {
        // TODO: Implement actual health check via ng control
        return checkPodReady(namespace, "rtpengine");
    }

    private boolean checkCoturnHealth(String namespace) {
        // TODO: Implement actual health check
        return checkPodReady(namespace, "coturn");
    }

    private boolean checkPodReady(String namespace, String appLabel) {
        try {
            var pods = k8sClient.pods()
                    .inNamespace(namespace)
                    .withLabel("app", appLabel)
                    .list()
                    .getItems();

            return pods.stream()
                    .anyMatch(pod -> pod.getStatus() != null
                            && pod.getStatus().getConditions() != null
                            && pod.getStatus().getConditions().stream()
                            .anyMatch(c -> "Ready".equals(c.getType())
                                    && "True".equals(c.getStatus())));
        } catch (Exception e) {
            log.warn("Error checking pod readiness for {}: {}", appLabel, e.getMessage());
            return false;
        }
    }

    private String generateEslPassword(Tenant tenant) {
        // Generate a deterministic but secure password based on tenant
        return "esl_" + tenant.getId().toString().substring(0, 8) + "_secure";
    }

    /**
     * Update telecom configs for an existing deployment (hot reload)
     */
    public void updateTelecomConfig(UUID tenantId) {
        /*
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ProvisioningException("Tenant not found: " + tenantId));

        String namespace = tenant.getNamespace();
        TelecomStackConfig stackConfig = buildStackConfig(tenant);

        // Regenerate configs
        String kamailioConfig = kamailioConfigBuilder.build(tenant, stackConfig);
        String freeSwitchDialplan = freeSwitchConfigBuilder.buildDialplan(tenant, stackConfig);

        // Update ConfigMaps
        k8sDeployer.updateConfigMap(namespace, "kamailio-config",
                Map.of("kamailio.cfg", kamailioConfig));
        k8sDeployer.updateConfigMap(namespace, "freeswitch-config",
                Map.of("dialplan.xml", freeSwitchDialplan));

        // Trigger reload
        reloadKamailio(namespace);
        reloadFreeSWITCH(namespace);

         */
    }

    private void reloadKamailio(String namespace) {
        try {
            String podName = findPodByLabel(namespace, "kamailio");
            k8sClient.pods()
                    .inNamespace(namespace)
                    .withName(podName)
                    .exec("kamcmd", "cfg.reload");
            log.info("Kamailio config reloaded in namespace {}", namespace);
        } catch (Exception e) {
            log.warn("Failed to reload Kamailio: {}", e.getMessage());
        }
    }

    private void reloadFreeSWITCH(String namespace) {
        try {
            String podName = findPodByLabel(namespace, "freeswitch");
            k8sClient.pods()
                    .inNamespace(namespace)
                    .withName(podName)
                    .exec("fs_cli", "-x", "reloadxml");
            log.info("FreeSWITCH config reloaded in namespace {}", namespace);
        } catch (Exception e) {
            log.warn("Failed to reload FreeSWITCH: {}", e.getMessage());
        }
    }

    private String findPodByLabel(String namespace, String appLabel) {
        return k8sClient.pods()
                .inNamespace(namespace)
                .withLabel("app", appLabel)
                .list()
                .getItems()
                .stream()
                .findFirst()
                .orElseThrow(() -> new ProvisioningException("Pod not found: " + appLabel))
                .getMetadata()
                .getName();
    }
}
