package com.dalai.llama.tenant.service.provisioning.telecom.k8s;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.provisioning.telecom.model.TelecomStackConfig;


import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.provisioning.telecom.model.TelecomStackConfig;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Kubernetes deployer for Agent Service.
 *
 * Agent Service handles:
 * - Agent state management (login/logout, ready/not-ready, break codes)
 * - Agent presence and availability
 * - Skills and proficiency management
 * - Agent statistics and performance metrics
 * - Real-time agent events via WebSocket
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentServiceK8sDeployer implements K8sDeployer {

    private final KubernetesClient k8sClient;

    @Value("${telecom.images.agent-service:dalaillama/agent-service:latest}")
    private String agentServiceImage;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @Value("${keycloak.base-url:https://auth.dalaillama.in}")
    private String keycloakBaseUrl;

    private static final String APP_NAME = "agent-service";

    @Override
    public String name() {
        return APP_NAME;
    }

    @Override
    public void deploy(String namespace, Tenant tenant, TelecomStackConfig config) {
        log.info("Deploying Agent Service for tenant {} in namespace {}", tenant.getSlug(), namespace);

        try {
            // 1. Create ConfigMap with application config
            createConfigMap(namespace, tenant, config);

            // 2. Create Deployment
            createDeployment(namespace, tenant, config);

            // 3. Create ClusterIP Service
            createService(namespace, tenant);

            // 4. Create Istio VirtualService for routing
            createVirtualService(namespace, tenant);

            log.info("Agent Service deployed successfully for tenant {}", tenant.getSlug());

        } catch (Exception e) {
            log.error("Failed to deploy Agent Service for tenant {}: {}", tenant.getSlug(), e.getMessage(), e);
            throw new RuntimeException("Agent Service deployment failed: " + e.getMessage(), e);
        }
    }

    private void createConfigMap(String namespace, Tenant tenant, TelecomStackConfig config) {
        String applicationYaml = buildApplicationConfig(namespace, tenant, config);

        ConfigMap cm = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(APP_NAME + "-config")
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "app", APP_NAME,
                        "tenant", tenant.getSlug(),
                        "app.kubernetes.io/managed-by", "dalai-llama"
                ))
                .endMetadata()
                .withData(Map.of("application.yaml", applicationYaml))
                .build();

        k8sClient.configMaps().inNamespace(namespace).resource(cm).createOrReplace();
        log.debug("Created ConfigMap for Agent Service");
    }

    private String buildApplicationConfig(String namespace, Tenant tenant, TelecomStackConfig config) {
        String pbxCoreUrl = config.getPbxCoreUrl() != null
                ? config.getPbxCoreUrl()
                : "http://pbx-core." + namespace + ".svc.cluster.local:8080";

        return String.format("""
            spring:
              application:
                name: agent-service
              kafka:
                bootstrap-servers: %s
                producer:
                  key-serializer: org.apache.kafka.common.serialization.StringSerializer
                  value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
                consumer:
                  group-id: agent-service-%s
                  auto-offset-reset: earliest
              data:
                redis:
                  url: %s
              security:
                oauth2:
                  resourceserver:
                    jwt:
                      issuer-uri: %s/realms/tenant-%s

            # Tenant Configuration
            tenant:
              id: %s
              slug: %s

            # PBX Core Integration
            pbx-core:
              url: %s
              timeout: 30000

            # Agent Configuration
            agent:
              # State management
              state:
                default-break-codes:
                  - code: LUNCH
                    description: Lunch Break
                    max-duration: 3600
                  - code: SHORT
                    description: Short Break
                    max-duration: 900
                  - code: TRAINING
                    description: Training
                    max-duration: 7200
                  - code: MEETING
                    description: Meeting
                    max-duration: 3600
                idle-timeout: 300
                auto-logout-after: 28800
              
              # Skills routing
              skills:
                enabled: %s
                default-proficiency: 50
                max-proficiency: 100
              
              # Capacity
              max-agents: %d
              max-concurrent-sessions: %d

            # Presence Configuration
            presence:
              heartbeat-interval: 30
              offline-threshold: 90
              sync-to-redis: true

            # Kafka Topics
            kafka:
              topics:
                agent-events: %s-agent-events
                agent-state: %s-agent-state
                agent-stats: %s-agent-stats
                presence-events: %s-presence-events

            # Server Configuration
            server:
              port: 8080

            # Actuator
            management:
              endpoints:
                web:
                  exposure:
                    include: health,info,prometheus
              endpoint:
                health:
                  show-details: always
                  probes:
                    enabled: true
            """,
                // Kafka & Redis
                config.getKafkaBootstrap(),
                tenant.getSlug(),
                config.getRedisUrl(),
                // Keycloak
                keycloakBaseUrl,
                tenant.getSlug(),
                // Tenant
                config.getTenantId(),
                config.getTenantSlug(),
                // PBX Core
                pbxCoreUrl,
                // Skills
                config.isEnableSkillsRouting(),
                // Capacity
                config.getMaxAgents() > 0 ? config.getMaxAgents() : 100,
                config.getMaxConcurrentCalls() > 0 ? config.getMaxConcurrentCalls() : 50,
                // Kafka Topics
                tenant.getSlug(), tenant.getSlug(), tenant.getSlug(), tenant.getSlug()
        );
    }

    private void createDeployment(String namespace, Tenant tenant, TelecomStackConfig config) {
        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName(APP_NAME)
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "app", APP_NAME,
                        "tenant", tenant.getSlug(),
                        "version", "v1",
                        "app.kubernetes.io/name", APP_NAME,
                        "app.kubernetes.io/component", "backend",
                        "app.kubernetes.io/managed-by", "dalai-llama"
                ))
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("app", APP_NAME))
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(Map.of(
                        "app", APP_NAME,
                        "tenant", tenant.getSlug(),
                        "version", "v1"
                ))
                .withAnnotations(Map.of(
                        "sidecar.istio.io/inject", "true",
                        "prometheus.io/scrape", "true",
                        "prometheus.io/port", "8080",
                        "prometheus.io/path", "/actuator/prometheus"
                ))
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName(APP_NAME)
                .withImage(agentServiceImage)
                .withImagePullPolicy("IfNotPresent")
                .addNewPort()
                .withName("http")
                .withContainerPort(8080)
                .withProtocol("TCP")
                .endPort()
                // Environment Variables
                .addNewEnv().withName("SPRING_CONFIG_LOCATION").withValue("/config/application.yaml").endEnv()
                .addNewEnv().withName("TENANT_ID").withValue(config.getTenantId()).endEnv()
                .addNewEnv().withName("TENANT_SLUG").withValue(config.getTenantSlug()).endEnv()
                .addNewEnv().withName("JAVA_OPTS").withValue("-Xms128m -Xmx384m").endEnv()
                // Volume Mounts
                .addNewVolumeMount()
                .withName("config")
                .withMountPath("/config")
                .withReadOnly(true)
                .endVolumeMount()
                // Resources
                .withNewResources()
                .addToRequests("cpu", new Quantity("50m"))
                .addToRequests("memory", new Quantity("128Mi"))
                .addToLimits("cpu", new Quantity("500m"))
                .addToLimits("memory", new Quantity("512Mi"))
                .endResources()
                // Probes
                .withNewLivenessProbe()
                .withNewHttpGet()
                .withPath("/actuator/health/liveness")
                .withNewPort(8080)
                .endHttpGet()
                .withInitialDelaySeconds(45)
                .withPeriodSeconds(30)
                .withTimeoutSeconds(10)
                .withFailureThreshold(3)
                .endLivenessProbe()
                .withNewReadinessProbe()
                .withNewHttpGet()
                .withPath("/actuator/health/readiness")
                .withNewPort(8080)
                .endHttpGet()
                .withInitialDelaySeconds(20)
                .withPeriodSeconds(10)
                .withTimeoutSeconds(5)
                .withFailureThreshold(3)
                .endReadinessProbe()
                .withNewStartupProbe()
                .withNewHttpGet()
                .withPath("/actuator/health")
                .withNewPort(8080)
                .endHttpGet()
                .withInitialDelaySeconds(10)
                .withPeriodSeconds(10)
                .withTimeoutSeconds(5)
                .withFailureThreshold(20)
                .endStartupProbe()
                .endContainer()
                // Volumes
                .addNewVolume()
                .withName("config")
                .withNewConfigMap()
                .withName(APP_NAME + "-config")
                .endConfigMap()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).createOrReplace();
        log.debug("Created Deployment for Agent Service");
    }

    private void createService(String namespace, Tenant tenant) {
        Service svc = new ServiceBuilder()
                .withNewMetadata()
                .withName(APP_NAME)
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "app", APP_NAME,
                        "tenant", tenant.getSlug()
                ))
                .endMetadata()
                .withNewSpec()
                .withType("ClusterIP")
                .withSelector(Map.of("app", APP_NAME))
                .addNewPort()
                .withName("http")
                .withPort(8080)
                .withTargetPort(new IntOrString(8080))
                .withProtocol("TCP")
                .endPort()
                .endSpec()
                .build();

        k8sClient.services().inNamespace(namespace).resource(svc).createOrReplace();
        log.debug("Created Service for Agent Service");
    }

    /**
     * Create Istio VirtualService for Agent API routing
     * Routes: https://api.{tenant}.{baseDomain}/agent/* -> agent-service:8080
     */
    private void createVirtualService(String namespace, Tenant tenant) {
        String host = "api." + tenant.getSlug() + "." + baseDomain;

        String vsYaml = String.format("""
            apiVersion: networking.istio.io/v1beta1
            kind: VirtualService
            metadata:
              name: %s-vs
              namespace: %s
              labels:
                app: %s
                tenant: %s
            spec:
              hosts:
              - "%s"
              gateways:
              - istio-system/main-gateway
              http:
              # Agent state management
              - match:
                - uri:
                    prefix: /agent/api/v1/state
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
                timeout: 30s
              # Agent skills
              - match:
                - uri:
                    prefix: /agent/api/v1/skills
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
              # Agent presence
              - match:
                - uri:
                    prefix: /agent/api/v1/presence
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
              # Agent statistics
              - match:
                - uri:
                    prefix: /agent/api/v1/stats
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
              # Agent profiles
              - match:
                - uri:
                    prefix: /agent/api/v1/profiles
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
              # Break codes
              - match:
                - uri:
                    prefix: /agent/api/v1/breaks
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
              # WebSocket for real-time agent events
              - match:
                - uri:
                    prefix: /agent/ws
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
                timeout: 3600s
              # Health & metrics
              - match:
                - uri:
                    prefix: /agent/actuator
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
              # Default catch-all for /agent
              - match:
                - uri:
                    prefix: /agent
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
            """,
                APP_NAME, namespace, APP_NAME, tenant.getSlug(),
                host,
                APP_NAME, APP_NAME, APP_NAME, APP_NAME, APP_NAME,
                APP_NAME, APP_NAME, APP_NAME, APP_NAME
        );

        k8sClient.load(new java.io.ByteArrayInputStream(vsYaml.getBytes()))
                .inNamespace(namespace)
                .createOrReplace();

        log.info("Created VirtualService for Agent Service: https://{}/agent/*", host);
    }

    @Override
    public void delete(String namespace) {
        log.info("Deleting Agent Service resources in namespace {}", namespace);

        try {
            // Delete VirtualService
            k8sClient.genericKubernetesResources("networking.istio.io/v1beta1", "VirtualService")
                    .inNamespace(namespace)
                    .withName(APP_NAME + "-vs")
                    .delete();

            // Delete Service
            k8sClient.services().inNamespace(namespace).withName(APP_NAME).delete();

            // Delete Deployment
            k8sClient.apps().deployments().inNamespace(namespace).withName(APP_NAME).delete();

            // Delete ConfigMap
            k8sClient.configMaps().inNamespace(namespace).withName(APP_NAME + "-config").delete();

            log.info("Deleted Agent Service resources in namespace {}", namespace);
        } catch (Exception e) {
            log.warn("Error deleting Agent Service resources: {}", e.getMessage());
        }
    }

    @Override
    public boolean isHealthy(String namespace) {
        try {
            var deployment = k8sClient.apps().deployments()
                    .inNamespace(namespace)
                    .withName(APP_NAME)
                    .get();

            if (deployment == null) {
                return false;
            }

            var status = deployment.getStatus();
            return status != null
                    && status.getReadyReplicas() != null
                    && status.getReadyReplicas() >= 1
                    && status.getAvailableReplicas() != null
                    && status.getAvailableReplicas() >= 1;
        } catch (Exception e) {
            log.warn("Error checking Agent Service health: {}", e.getMessage());
            return false;
        }
    }
}