package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.DeploymentModel;
import com.dalai.llama.tenant.domain.exception.ProvisioningException;
import com.dalai.llama.tenant.domain.exception.TenantNotFoundException;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.service.KubernetesProvisioningService;
import com.dalai.llama.tenant.service.provisioning.telecom.TelecomStackProvisioner;
import com.dalai.llama.tenant.service.provisioning.telecom.k8s.AgentServiceK8sDeployer;
import com.dalai.llama.tenant.service.provisioning.telecom.k8s.AgentUiK8sDeployer;
import com.dalai.llama.tenant.service.provisioning.telecom.k8s.AiServiceK8sDeployer;
import com.dalai.llama.tenant.service.provisioning.telecom.k8s.PbxCoreK8sDeployer;
import com.dalai.llama.tenant.service.provisioning.telecom.model.CCBuilderConfig;
import com.dalai.llama.tenant.service.provisioning.telecom.model.TelecomStackConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KubernetesProvisioningServiceImpl implements KubernetesProvisioningService {

    private final KubernetesClient kubernetesClient;
    private final TenantRepository tenantRepository;

    // Inject deployers
    private final PbxCoreK8sDeployer pbxCoreDeployer;
    private final AgentServiceK8sDeployer agentServiceDeployer;
    private final AiServiceK8sDeployer aiServiceDeployer;
    private final AgentUiK8sDeployer agentUiDeployer;
    private final TelecomStackProvisioner telecomStackProvisioner;
    private final ObjectMapper objectMapper;

    @Value("${provisioning.ip-wait-timeout-minutes:10}")
    private int ipWaitTimeoutMinutes;

    @Value("${provisioning.ip-poll-interval-seconds:10}")
    private int ipPollIntervalSeconds;

    @Value("${sip.domain:sip.dalaillama.in}")
    private String sipDomain;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    // Add new @Value fields
    @Value("${provisioning.shared.telecom-namespace:platform-telecom}")
    private String sharedTelecomNamespace;

    @Value("${provisioning.shared.esl-password:ClueCon}")
    private String sharedEslPassword;


    @Override
    public void deployServices(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));

        String namespace = "shared";//tenant.getNamespace();
        TelecomStackConfig config = buildConfig(tenant);

        log.info("Deploying services for tenant {} in namespace {}", tenant.getSlug(), namespace);

        // Deploy in order (dependencies first)
        pbxCoreDeployer.deploy(namespace, tenant, config);
        agentServiceDeployer.deploy(namespace, tenant, config);
        aiServiceDeployer.deploy(namespace, tenant, config);
        agentUiDeployer.deploy(namespace, tenant, config);

        log.info("All services deployed for tenant {}", tenant.getSlug());
    }
    @Override
    public void createNamespace(UUID tenantId, String namespace) {
        log.info("Creating namespace {} for tenant {}", namespace, tenantId);

        Tenant tenant = findTenant(tenantId);

        try {
            // Check if namespace already exists
            if (kubernetesClient.namespaces().withName(namespace).get() != null) {
                log.info("Namespace {} already exists", namespace);
                return;
            }

            Namespace ns = new NamespaceBuilder()
                    .withNewMetadata()
                    .withName(namespace)
                    .withLabels(Map.of(
                            "tenant-id", tenantId.toString(),
                            "tenant-slug", tenant.getSlug(),
                            //"deployment-model", tenant.getDeploymentModel().name().toLowerCase(),
                            "managed-by", "dalai-llama"
                    ))
                    .endMetadata()
                    .build();

            kubernetesClient.namespaces().resource(ns).create();

            // Create resource quota based on plan
            createResourceQuota(namespace, tenant);

            // Create network policy
            createNetworkPolicy(namespace, tenant);

            // Update tenant with namespace
            //tenant.setNamespace(namespace);
            tenantRepository.save(tenant);

            log.info("Created namespace {} for tenant {}", namespace, tenantId);
        } catch (Exception e) {
            log.error("Failed to create namespace {} for tenant {}", namespace, tenantId, e);
            throw new ProvisioningException("Failed to create namespace: " + e.getMessage());
        }
    }

    @Override
    public void deployInfrastructure(UUID tenantId) {
/*        Tenant tenant = findTenant(tenantId);
        String namespace = "shared";//tenant.getNamespace();

        log.info("Deploying infrastructure for tenant {} in namespace {}", tenantId, namespace);

        try {
            if (tenant.getDeploymentModel() == DeploymentModel.DEDICATED) {
                // Deploy dedicated infrastructure
                deployRedis(namespace, tenant);
                deployPostgres(namespace, tenant);

                // Update tenant with infra URLs
                tenant.setRedisUrl("redis://redis." + namespace + ".svc.cluster.local:6379");
                tenant.setPostgresUrl("jdbc:postgresql://postgres." + namespace + ".svc.cluster.local:5432/tenant_db");
            } else {
                // Use shared infrastructure (URLs from platform config)
                tenant.setRedisUrl("redis://redis.platform.svc.cluster.local:6379");
                tenant.setPostgresUrl("jdbc:postgresql://postgres.platform.svc.cluster.local:5432/" + tenant.getSlug() + "_db");
            }

            // Create Kafka topics (in shared Kafka cluster)
            createKafkaTopics(tenant);

            tenantRepository.save(tenant);
            log.info("Deployed infrastructure for tenant {}", tenantId);
        } catch (Exception e) {
            log.error("Failed to deploy infrastructure for tenant {}", tenantId, e);
            throw new ProvisioningException("Failed to deploy infrastructure: " + e.getMessage());
        }

 */
    }

    @Override
    public void deployTelecom(UUID tenantId) {
        Tenant tenant = findTenant(tenantId);
        String namespace = "shared";//tenant.getNamespace();

        log.info("Deploying telecom stack for tenant {} in namespace {}", tenantId, namespace);

        try {

            // Update tenant with telecom endpoints
            executeDeployTelecomStackStep(tenant);
            //tenant.setRtpengineSock("udp://rtpengine." + namespace + ".svc.cluster.local:22222");
            //tenant.setWebsocketUrl("wss://" + tenant.getSlug() + ".wss.dalaillama.in");
            tenantRepository.save(tenant);

            log.info("Deployed telecom stack for tenant {}", tenantId);
        } catch (Exception e) {
            log.error("Failed to deploy telecom stack for tenant {}", tenantId, e);
            throw new ProvisioningException("Failed to deploy telecom: " + e.getMessage());
        }
    }

    @Override
    public String waitForExternalIp(UUID tenantId) {
        Tenant tenant = findTenant(tenantId);
        String namespace = "shared";//tenant.getNamespace();
        String serviceName = "sip-lb";

        log.info("Waiting for external IP for tenant {} in namespace {}", tenantId, namespace);

        Instant deadline = Instant.now().plus(Duration.ofMinutes(ipWaitTimeoutMinutes));

        while (Instant.now().isBefore(deadline)) {
            try {
                io.fabric8.kubernetes.api.model.Service k8sService = kubernetesClient.services()
                        .inNamespace(namespace)
                        .withName(serviceName)
                        .get();

                if (k8sService != null && k8sService.getStatus() != null
                        && k8sService.getStatus().getLoadBalancer() != null
                        && k8sService.getStatus().getLoadBalancer().getIngress() != null
                        && !k8sService.getStatus().getLoadBalancer().getIngress().isEmpty()) {

                    LoadBalancerIngress ingress = k8sService.getStatus().getLoadBalancer().getIngress().get(0);
                    String externalIp = ingress.getIp() != null ? ingress.getIp() : ingress.getHostname();

                    if (externalIp != null && !externalIp.isEmpty()) {
                        log.info("External IP assigned for tenant {}: {}", tenantId, externalIp);

                        // Update tenant with SIP URLs
                        //tenant.setSipExternalIp(externalIp);
                        //tenant.setSipUdpUrl("sip:" + externalIp + ":5060");
                        //tenant.setSipTlsUrl("sips:" + externalIp + ":5061");
                        //tenant.setTurnUrl("turn:" + externalIp + ":3478");
                        tenantRepository.save(tenant);

                        return externalIp;
                    }
                }

                Thread.sleep(ipPollIntervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProvisioningException("Interrupted while waiting for external IP");
            } catch (Exception e) {
                log.warn("Error checking for external IP: {}", e.getMessage());
            }
        }

        throw new ProvisioningException("Timeout waiting for external IP after " + ipWaitTimeoutMinutes + " minutes");
    }

    // ========== Cleanup Methods (for compensation) ==========

    public void deleteNamespace(String namespace) {
        log.info("Deleting namespace: {}", namespace);
        try {
            kubernetesClient.namespaces().withName(namespace).delete();
        } catch (Exception e) {
            log.warn("Failed to delete namespace {}: {}", namespace, e.getMessage());
        }
    }

    // ========== Private Deployment Methods ==========

    private void createResourceQuota(String namespace, Tenant tenant) {
        ResourceQuota quota = new ResourceQuotaBuilder()
                .withNewMetadata()
                .withName("tenant-quota")
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .addToHard("requests.cpu", new Quantity("4"))
                .addToHard("requests.memory", new Quantity("8Gi"))
                .addToHard("limits.cpu", new Quantity("8"))
                .addToHard("limits.memory", new Quantity("16Gi"))
                .addToHard("pods", new Quantity("20"))
                .addToHard("persistentvolumeclaims", new Quantity("10"))
                .endSpec()
                .build();

        kubernetesClient.resourceQuotas().inNamespace(namespace).resource(quota).create();
    }

    private void createNetworkPolicy(String namespace, Tenant tenant) {
        log.debug("Network policy creation for {} - using Istio policies", namespace);
    }

    private void deployRedis(String namespace, Tenant tenant) {
        log.debug("Deploying Redis in namespace: {}", namespace);

        Deployment redis = new DeploymentBuilder()
                .withNewMetadata()
                .withName("redis")
                .withNamespace(namespace)
                .withLabels(Map.of("app", "redis", "tenant", tenant.getSlug()))
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("app", "redis"))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of("app", "redis"))
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("redis")
                .withImage("redis:7.2-alpine")
                .addNewPort()
                .withContainerPort(6379)
                .endPort()
                .withNewResources()
                .addToRequests("cpu", new Quantity("100m"))
                .addToRequests("memory", new Quantity("128Mi"))
                .addToLimits("cpu", new Quantity("500m"))
                .addToLimits("memory", new Quantity("512Mi"))
                .endResources()
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        kubernetesClient.apps().deployments().inNamespace(namespace).resource(redis).create();
        createClusterIpService(namespace, "redis", 6379);
    }

    private void deployPostgres(String namespace, Tenant tenant) {
        log.debug("Deploying PostgreSQL in namespace: {}", namespace);
        // Using StatefulSet for PostgreSQL with PVC - implementation omitted for brevity
    }


    /**
     * Deploy telecom stack - handles both SHARED and DEDICATED models
     */
    private Map<String, Object> executeDeployTelecomStackStep(Tenant tenant) {
        //log.info("Deploying telecom stack for tenant {} (model: {})",
        //        tenant.getId(), tenant.getDeploymentModel());

        String externalIp;
/*
        if (tenant.getDeploymentModel() == DeploymentModel.SHARED) {
            // For SHARED: Check if shared stack exists, deploy if not, then configure tenant
            if (!isSharedTelecomStackReady()) {
                log.info("Shared telecom stack not found in namespace {}. Deploying...", tenant.getNamespace());
                externalIp = deploySharedTelecomStack();
            } else {
                log.info("Shared telecom stack already exists. Using existing stack.");
                externalIp = getSharedExternalIp();
            }

            // Configure tenant in shared stack
            configureSharedTelecomForTenant(tenant, externalIp);

            return Map.of(
                    "deploymentModel", "SHARED",
                    //"sharedNamespace", tenant.getNamespace(),
                    "externalIp", externalIp
                   // "sipUdpUrl", tenant.getSipUdpUrl(),
                   // "sipTlsUrl", tenant.getSipTlsUrl(),
                   // "turnUrl", tenant.getTurnUrl(),
                   // "websocketUrl", tenant.getWebsocketUrl(),
                   // "eslHost", tenant.getFreeswitchEslHost()
            );

        } else {
            // For DEDICATED: Deploy full stack in tenant's namespace
            externalIp = telecomStackProvisioner.deployTelecomStack(tenant.getId());

            // Reload tenant to get updated endpoints
            tenant = tenantRepository.findById(tenant.getId()).orElseThrow();

            return Map.of(
                    "deploymentModel", "DEDICATED"
                  //  "namespace", tenant.getNamespace(),
                   // "externalIp", externalIp,
                    //"sipUdpUrl", tenant.getSipUdpUrl(),
                    //"sipTlsUrl", tenant.getSipTlsUrl(),
                   // "turnUrl", tenant.getTurnUrl(),
                   // "websocketUrl", tenant.getWebsocketUrl(),
                   // "eslHost", tenant.getFreeswitchEslHost(),
                   // "eslPort", tenant.getFreeswitchEslPort()
            );
        } */
         return Map.of();
    }

    /**
     * Check if shared telecom stack is deployed and healthy
     */
    private boolean isSharedTelecomStackReady() {
        try {
            // Check if namespace exists
            if (kubernetesClient.namespaces().withName(sharedTelecomNamespace).get() == null) {
                log.debug("Shared namespace {} does not exist", sharedTelecomNamespace);
                return false;
            }

            // Check Kamailio deployment
            var kamailioDeployment = kubernetesClient.apps().deployments()
                    .inNamespace(sharedTelecomNamespace)
                    .withName("kamailio")
                    .get();

            if (kamailioDeployment == null ||
                    kamailioDeployment.getStatus() == null ||
                    kamailioDeployment.getStatus().getReadyReplicas() == null ||
                    kamailioDeployment.getStatus().getReadyReplicas() < 1) {
                log.debug("Shared Kamailio not ready in namespace {}", sharedTelecomNamespace);
                return false;
            }

            // Check FreeSWITCH deployment
            var freeswitchDeployment = kubernetesClient.apps().deployments()
                    .inNamespace(sharedTelecomNamespace)
                    .withName("freeswitch")
                    .get();

            if (freeswitchDeployment == null ||
                    freeswitchDeployment.getStatus() == null ||
                    freeswitchDeployment.getStatus().getReadyReplicas() == null ||
                    freeswitchDeployment.getStatus().getReadyReplicas() < 1) {
                log.debug("Shared FreeSWITCH not ready in namespace {}", sharedTelecomNamespace);
                return false;
            }

            // Check LoadBalancer has external IP
            var sipLbService = kubernetesClient.services()
                    .inNamespace(sharedTelecomNamespace)
                    .withName("kamailio-sip-lb")
                    .get();

            if (sipLbService == null ||
                    sipLbService.getStatus() == null ||
                    sipLbService.getStatus().getLoadBalancer() == null ||
                    sipLbService.getStatus().getLoadBalancer().getIngress() == null ||
                    sipLbService.getStatus().getLoadBalancer().getIngress().isEmpty()) {
                log.debug("Shared SIP LoadBalancer has no external IP");
                return false;
            }

            String ip = sipLbService.getStatus().getLoadBalancer().getIngress().get(0).getIp();
            if (ip == null || ip.isEmpty()) {
                ip = sipLbService.getStatus().getLoadBalancer().getIngress().get(0).getHostname();
            }

            if (ip == null || ip.isEmpty()) {
                log.debug("Shared SIP LoadBalancer IP/hostname is empty");
                return false;
            }

            log.info("Shared telecom stack is ready in namespace {} with IP {}", sharedTelecomNamespace, ip);
            return true;

        } catch (Exception e) {
            log.warn("Error checking shared telecom stack: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Deploy shared telecom stack in platform namespace
     */
    private String deploySharedTelecomStack() {
        log.info("Deploying shared telecom stack in namespace: {}", sharedTelecomNamespace);

        try {
            // Use TelecomStackProvisioner to deploy shared stack
            return telecomStackProvisioner.deploySharedTelecomStack(sharedTelecomNamespace);

        } catch (Exception e) {
            log.error("Failed to deploy shared telecom stack: {}", e.getMessage(), e);
            throw new ProvisioningException("Failed to deploy shared telecom stack: " + e.getMessage(), e);
        }
    }

    /**
     * Get external IP from existing shared stack
     */
    private String getSharedExternalIp() {
        var service = kubernetesClient.services()
                .inNamespace(sharedTelecomNamespace)
                .withName("kamailio-sip-lb")
                .get();

        if (service != null &&
                service.getStatus() != null &&
                service.getStatus().getLoadBalancer() != null &&
                service.getStatus().getLoadBalancer().getIngress() != null &&
                !service.getStatus().getLoadBalancer().getIngress().isEmpty()) {

            var ingress = service.getStatus().getLoadBalancer().getIngress().get(0);
            String ip = ingress.getIp() != null ? ingress.getIp() : ingress.getHostname();

            if (ip != null && !ip.isEmpty()) {
                return ip;
            }
        }

        throw new ProvisioningException("Shared LoadBalancer IP not found");
    }

    /**
     * Configure tenant-specific settings in shared telecom stack
     */
    private void configureSharedTelecomForTenant(Tenant tenant, String externalIp) {
        log.info("Configuring shared telecom stack for tenant {}", tenant.getId());
/*
        try {
            // Update tenant with shared endpoints
            tenant.setSipExternalIp(externalIp);
            tenant.setSipUdpUrl("sip:" + externalIp + ":5060");
            tenant.setSipTlsUrl("sips:" + externalIp + ":5061");
            tenant.setTurnUrl("turn:" + externalIp + ":3478");
            tenant.setWebsocketUrl("wss://" + tenant.getSlug() + ".wss." + baseDomain);
            tenant.setRtpengineSock("udp://rtpengine." + sharedTelecomNamespace + ".svc.cluster.local:22222");
            tenant.setKafkaBootstrap("kafka.platform.svc.cluster.local:9092");
            tenant.setRedisUrl("redis://redis.platform.svc.cluster.local:6379");

            // Shared FreeSWITCH ESL
            tenant.setFreeswitchEslHost("freeswitch." + sharedTelecomNamespace + ".svc.cluster.local");
            tenant.setFreeswitchEslPort(8021);
            tenant.setFreeswitchEslPassword(sharedEslPassword);

            tenantRepository.save(tenant);

            // Register tenant routing in shared stack (via PBX-Core API)
            registerTenantInSharedStack(tenant);

            log.info("Tenant {} configured in shared telecom stack. External IP: {}",
                    tenant.getId(), externalIp);

        } catch (Exception e) {
            log.error("Failed to configure shared telecom for tenant {}: {}",
                    tenant.getId(), e.getMessage());
            throw new ProvisioningException("Failed to configure shared telecom: " + e.getMessage(), e);
        }

 */
    }

    /**
     * Register tenant routing in shared Kamailio/FreeSWITCH
     * The shared stack uses mod_xml_curl to fetch tenant config dynamically from PBX-Core
     */
    private void registerTenantInSharedStack(Tenant tenant) {
        log.info("Registering tenant {} in shared telecom stack", tenant.getSlug());

        // Shared Kamailio/FreeSWITCH fetch routing dynamically from PBX-Core via:
        // - Kamailio: htable or dispatcher populated from PBX-Core API
        // - FreeSWITCH: mod_xml_curl fetches dialplan/directory from PBX-Core
        //
        // The tenant registration in PBX-Core happens via tenant-service events
        // which PBX-Core listens to on Kafka topic: tenant.created, tenant.activated
        //
        // No direct action needed here - PBX-Core handles tenant routing setup
        // when it receives the TenantCreatedEvent and TenantActivatedEvent

        log.debug("Tenant {} will be dynamically routed via PBX-Core", tenant.getSlug());
    }


    private void createClusterIpService(String namespace, String name, int port) {
        io.fabric8.kubernetes.api.model.Service service = new ServiceBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withType("ClusterIP")
                .withSelector(Map.of("app", name))
                .addNewPort()
                .withPort(port)
                .withTargetPort(new IntOrString(port))
                .endPort()
                .endSpec()
                .build();

        kubernetesClient.services().inNamespace(namespace).resource(service).create();
    }

    private void createKafkaTopics(Tenant tenant) {
        log.info("Creating Kafka topics for tenant: {}", tenant.getSlug());
    }


    private Tenant findTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
    }

    /**
     * Build TelecomStackConfig from Tenant entity
     * Uses CCBuilderConfig if available, otherwise falls back to defaults
     */
    private TelecomStackConfig buildConfig(Tenant tenant) {
        /*
        String namespace = tenant.getNamespace();
        boolean isDedicated = tenant.getDeploymentModel() == DeploymentModel.DEDICATED;

        // Try to parse CCBuilderConfig if available
        CCBuilderConfig ccConfig = null;
        if (tenant.getCcBuilderConfig() != null && !tenant.getCcBuilderConfig().isBlank()) {
            try {
                ccConfig = objectMapper.readValue(tenant.getCcBuilderConfig(), CCBuilderConfig.class);
            } catch (Exception e) {
                log.warn("Failed to parse CCBuilderConfig for tenant {}, using defaults", tenant.getSlug());
            }
        }

        // If CCBuilderConfig exists, use its conversion method
        if (ccConfig != null) {
            String pbxCoreUrl = "http://pbx-core." + namespace + ".svc.cluster.local:8080";
            String aiServiceUrl = "http://ai-service." + namespace + ".svc.cluster.local:8080";
            String kafkaBootstrap = tenant.getKafkaBootstrap() != null
                    ? tenant.getKafkaBootstrap()
                    : (isDedicated ? "kafka." + namespace + ".svc.cluster.local:9092" : "kafka.platform.svc.cluster.local:9092");
            String redisUrl = tenant.getRedisUrl() != null
                    ? tenant.getRedisUrl()
                    : (isDedicated ? "redis://" + namespace + "-redis:6379" : "redis://redis.platform.svc.cluster.local:6379");

            TelecomStackConfig config = ccConfig.toTelecomStackConfig(
                    tenant.getId().toString(),
                    tenant.getSlug(),
                    pbxCoreUrl,
                    aiServiceUrl,
                    kafkaBootstrap,
                    redisUrl
            );

            // Override with tenant-specific values
            config.setNamespace(namespace);
            config.setEslPassword(tenant.getFreeswitchEslPassword() != null
                    ? tenant.getFreeswitchEslPassword()
                    : "esl_" + tenant.getId().toString().substring(0, 8) + "_secure");
            config.setAgentServiceUrl("http://agent-service." + namespace + ".svc.cluster.local:8080");

            return config;
        }

        // Fallback: build from Tenant fields directly
        return buildConfigFromTenant(tenant, namespace, isDedicated);

         */
        return null;
    }

    /**
     * Build config directly from Tenant entity when CCBuilderConfig is not available
     */
    private TelecomStackConfig buildConfigFromTenant(Tenant tenant, String namespace, boolean isDedicated) {
        return TelecomStackConfig.defaultConfig(tenant.getId().toString(), tenant.getSlug())
                // Override with actual values
                .namespace(namespace)
                .isDedicated(isDedicated)

                // Service URLs
                .pbxCoreUrl("http://pbx-core." + namespace + ".svc.cluster.local:8080")
                .agentServiceUrl("http://agent-service." + namespace + ".svc.cluster.local:8080")
                .aiServiceUrl("http://ai-service." + namespace + ".svc.cluster.local:8080")
/*
                // Kafka
                .kafkaBootstrap(tenant.getKafkaBootstrap() != null
                        ? tenant.getKafkaBootstrap()
                        : (isDedicated ? "kafka." + namespace + ".svc.cluster.local:9092" : "kafka.platform.svc.cluster.local:9092"))
                .kafkaTopicPrefix(tenant.getSlug())

                // Redis
                .redisUrl(tenant.getRedisUrl() != null
                        ? tenant.getRedisUrl()
                        : (isDedicated ? "redis://" + namespace + "-redis:6379" : "redis://redis.platform.svc.cluster.local:6379"))

                // FreeSWITCH ESL
                .eslPort(tenant.getFreeswitchEslPort() != null ? tenant.getFreeswitchEslPort() : 8021)
                .eslPassword(tenant.getFreeswitchEslPassword() != null
                        ? tenant.getFreeswitchEslPassword()
                        : "esl_" + tenant.getId().toString().substring(0, 8) + "_secure")

                // AI Features from Tenant
                .enableAiTranscription(tenant.isAiTranscriptionEnabled())
                .enableAiRouting(tenant.isAiRoutingEnabled())
                .enableAiNoiseCancellation(tenant.isAiNoiseCancellationEnabled())
                .enableAiSentiment(tenant.isAiTranscriptionEnabled())
                .enableAiAgentAssist(tenant.isAiTranscriptionEnabled())
*/
                // Realm
                .realm(tenant.getRealm())

                .build();
    }
}