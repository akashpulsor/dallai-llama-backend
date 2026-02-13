package com.dalai.llama.tenant.service.provisioning.telecom.k8s;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.provisioning.telecom.model.TelecomStackConfig;


import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.provisioning.telecom.model.TelecomStackConfig;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Kubernetes deployer for PBX-Core service.
 *
 * PBX-Core is the central telephony control service that:
 * - Manages call routing and dialplans
 * - Provides REST API for agent/supervisor operations
 * - Integrates with FreeSWITCH via ESL
 * - Publishes events to Kafka
 * - Provides WebSocket for real-time updates
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PbxCoreK8sDeployer implements K8sDeployer {

    private final KubernetesClient k8sClient;

    @Value("${telecom.images.pbx-core:dalaillama/pbx-core:latest}")
    private String pbxCoreImage;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @Value("${cluster.issuer:letsencrypt-prod}")
    private String clusterIssuer;

    @Value("${ingress.class:nginx}")
    private String ingressClass;

    @Value("${keycloak.base-url:https://auth.dalaillama.in}")
    private String keycloakBaseUrl;

    private static final String APP_NAME = "pbx-core";

    @Override
    public String name() {
        return APP_NAME;
    }

    @Override
    public void deploy(String namespace, Tenant tenant, TelecomStackConfig config) {
        log.info("Deploying PBX-Core for tenant {} in namespace {}", tenant.getSlug(), namespace);

        try {
            // 1. Create ConfigMap with application config
            createConfigMap(namespace, tenant, config);

            // 2. Create Deployment
            createDeployment(namespace, tenant, config);

            // 3. Create ClusterIP Service
            createService(namespace, tenant);

            // 4. Create Istio VirtualService for API routing
            createVirtualService(namespace, tenant);

            createApiIngress(namespace, tenant);

            createWssIngress(namespace, tenant);
            log.info("PBX-Core deployed successfully for tenant {}", tenant.getSlug());

        } catch (Exception e) {
            log.error("Failed to deploy PBX-Core for tenant {}: {}", tenant.getSlug(), e.getMessage(), e);
            throw new RuntimeException("PBX-Core deployment failed: " + e.getMessage(), e);
        }
    }

    /**
     * Create Ingress for API access
     * URL: https://api.{tenant}.{baseDomain}
     */
    private void createApiIngress(String namespace, Tenant tenant) {
        String host = "api." + tenant.getSlug() + "." + baseDomain;
        String tlsSecretName = tenant.getSlug() + "-api-tls";

        Ingress ingress = new IngressBuilder()
                .withNewMetadata()
                .withName("api-ingress")
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "app", "pbx-core",
                        "tenant", tenant.getSlug()
                ))
                .withAnnotations(Map.of(
                        "kubernetes.io/ingress.class", ingressClass,
                        "cert-manager.io/cluster-issuer", clusterIssuer,
                        "nginx.ingress.kubernetes.io/ssl-redirect", "true",
                        "nginx.ingress.kubernetes.io/proxy-body-size", "50m",
                        "nginx.ingress.kubernetes.io/cors-allow-origin", "https://" + tenant.getSlug() + "." + baseDomain,
                        "nginx.ingress.kubernetes.io/enable-cors", "true"
                ))
                .endMetadata()
                .withNewSpec()
                .withIngressClassName(ingressClass)
                .addNewTl()
                .addToHosts(host)
                .withSecretName(tlsSecretName)
                .endTl()
                .addNewRule()
                .withHost(host)
                .withNewHttp()
                .addNewPath()
                .withPath("/api/")
                .withPathType("Prefix")
                .withNewBackend()
                .withNewService()
                .withName("pbx-core")
                .withNewPort().withNumber(8080).endPort()
                .endService()
                .endBackend()
                .endPath()
                .addNewPath()
                .withPath("/agent/")
                .withPathType("Prefix")
                .withNewBackend()
                .withNewService()
                .withName("agent-service")
                .withNewPort().withNumber(8080).endPort()
                .endService()
                .endBackend()
                .endPath()
                .endHttp()
                .endRule()
                .endSpec()
                .build();

        k8sClient.network().v1().ingresses().inNamespace(namespace).resource(ingress).createOrReplace();
        log.info("Created API Ingress for https://{}", host);
    }

    /**
     * Create Ingress for WebSocket/WebRTC
     * URL: wss://{tenant}.wss.{baseDomain}
     */
    private void createWssIngress(String namespace, Tenant tenant) {
        String host = tenant.getSlug() + ".wss." + baseDomain;
        String tlsSecretName = tenant.getSlug() + "-wss-tls";

        Ingress ingress = new IngressBuilder()
                .withNewMetadata()
                .withName("wss-ingress")
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "app", "kamailio",
                        "tenant", tenant.getSlug()
                ))
                .withAnnotations(Map.of(
                        "kubernetes.io/ingress.class", ingressClass,
                        "cert-manager.io/cluster-issuer", clusterIssuer,
                        "nginx.ingress.kubernetes.io/ssl-redirect", "true",
                        "nginx.ingress.kubernetes.io/proxy-http-version", "1.1",
                        "nginx.ingress.kubernetes.io/proxy-read-timeout", "3600",
                        "nginx.ingress.kubernetes.io/proxy-send-timeout", "3600",
                        "nginx.ingress.kubernetes.io/upstream-hash-by", "$binary_remote_addr",
                        "nginx.ingress.kubernetes.io/configuration-snippet",
                        "proxy_set_header Upgrade $http_upgrade;\n" +
                                "proxy_set_header Connection \"upgrade\";\n"
                ))
                .endMetadata()
                .withNewSpec()
                .withIngressClassName(ingressClass)
                .addNewTl()
                .addToHosts(host)
                .withSecretName(tlsSecretName)
                .endTl()
                .addNewRule()
                .withHost(host)
                .withNewHttp()
                .addNewPath()
                .withPath("/")
                .withPathType("Prefix")
                .withNewBackend()
                .withNewService()
                .withName("kamailio")
                .withNewPort().withNumber(8089).endPort()
                .endService()
                .endBackend()
                .endPath()
                .endHttp()
                .endRule()
                .endSpec()
                .build();

        k8sClient.network().v1().ingresses().inNamespace(namespace).resource(ingress).createOrReplace();
        log.info("Created WSS Ingress for wss://{}", host);
    }

    private void createConfigMap(String namespace, Tenant tenant, TelecomStackConfig config) {
        String applicationYaml = buildApplicationConfig(tenant, config);

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
        log.debug("Created ConfigMap for PBX-Core");
    }

    private String buildApplicationConfig(Tenant tenant, TelecomStackConfig config) {
        /*String eslHost = tenant.getFreeswitchEslHost() != null
                ? tenant.getFreeswitchEslHost()
                : "freeswitch." + tenant.getNamespace() + ".svc.cluster.local";*/
        String eslHost = "test";

        return String.format("""
            spring:
              application:
                name: pbx-core
              kafka:
                bootstrap-servers: %s
                producer:
                  key-serializer: org.apache.kafka.common.serialization.StringSerializer
                  value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
                consumer:
                  group-id: pbx-core-%s
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
              realm: %s

            # FreeSWITCH ESL Connection
// FreeSWITCH ESL Connection
            freeswitch:
              esl:
                host: %s
                port: %d
                password: %s
                timeout: 30000
                reconnect-delay: 5000

            # Feature Flags
            features:
              recording:
                enabled: %s
                path: %s
                format: %s
                stereo: %s
              ai:
                transcription: %s
                sentiment: %s
                agent-assist: %s
                noise-cancellation: %s
              conference:
                enabled: %s
                max-rooms: %d
                max-participants: %d
              supervisor:
                silent-monitor: %s
                whisper: %s
                barge: %s
                takeover: %s
              queues:
                skills-routing: %s
                priority-routing: %s
                callback: %s
                max-wait-time: %d

            # Kafka Topics
            kafka:
              topics:
                call-events: %s-call-events
                agent-events: %s-agent-events
                queue-events: %s-queue-events
                conference-events: %s-conference-events
                ai-results: %s-ai-results

            # Service URLs
            services:
              agent-service: http://agent-service:8080
              ai-service: http://ai-service:8080

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
                config.getRealm(),
                // FreeSWITCH ESL
                config.getEslPort() > 0 ? config.getEslPort() : 8021,
                config.getEslPassword() != null ? config.getEslPassword() : "ClueCon",
                // Recording
                config.isEnableRecording(),
                config.getRecordingPath() != null ? config.getRecordingPath() : "/recordings",
                config.getRecordingFormat() != null ? config.getRecordingFormat() : "wav",
                config.isRecordingStereo(),
                // AI
                config.isEnableAiTranscription(),
                config.isEnableAiSentiment(),
                config.isEnableAiAgentAssist(),
                config.isEnableAiNoiseCancellation(),
                // Conference
                config.isEnableConference(),
                config.getMaxConferenceRooms(),
                config.getMaxConferenceParticipants(),
                // Supervisor
                config.isEnableSilentMonitor(),
                config.isEnableWhisper(),
                config.isEnableBarge(),
                config.isEnableTakeover(),
                // Queues
                config.isEnableSkillsRouting(),
                config.isEnablePriorityRouting(),
                config.isEnableCallbackQueue(),
                config.getQueueMaxWaitTime(),
                // Kafka Topics
                tenant.getSlug(), tenant.getSlug(), tenant.getSlug(), tenant.getSlug(), tenant.getSlug()
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
                .withImage(pbxCoreImage)
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
                .addNewEnv().withName("JAVA_OPTS").withValue("-Xms256m -Xmx512m").endEnv()
                // Volume Mounts
                .addNewVolumeMount()
                .withName("config")
                .withMountPath("/config")
                .withReadOnly(true)
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("recordings")
                .withMountPath("/recordings")
                .endVolumeMount()
                // Resources
                .withNewResources()
                .addToRequests("cpu", new Quantity("100m"))
                .addToRequests("memory", new Quantity("256Mi"))
                .addToLimits("cpu", new Quantity("1000m"))
                .addToLimits("memory", new Quantity("1Gi"))
                .endResources()
                // Probes
                .withNewLivenessProbe()
                .withNewHttpGet()
                .withPath("/actuator/health/liveness")
                .withNewPort(8080)
                .endHttpGet()
                .withInitialDelaySeconds(60)
                .withPeriodSeconds(30)
                .withTimeoutSeconds(10)
                .withFailureThreshold(3)
                .endLivenessProbe()
                .withNewReadinessProbe()
                .withNewHttpGet()
                .withPath("/actuator/health/readiness")
                .withNewPort(8080)
                .endHttpGet()
                .withInitialDelaySeconds(30)
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
                .withFailureThreshold(30)
                .endStartupProbe()
                .endContainer()
                // Volumes
                .addNewVolume()
                .withName("config")
                .withNewConfigMap()
                .withName(APP_NAME + "-config")
                .endConfigMap()
                .endVolume()
                .addNewVolume()
                .withName("recordings")
                .withNewEmptyDir()
                .endEmptyDir()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).createOrReplace();
        log.debug("Created Deployment for PBX-Core");
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
        log.debug("Created Service for PBX-Core");
    }

    /**
     * Create Istio VirtualService for API routing
     * Routes: https://api.{tenant}.{baseDomain}/api/* -> pbx-core:8080
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
              # API routes
              - match:
                - uri:
                    prefix: /api/v1/calls
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
                timeout: 30s
                retries:
                  attempts: 3
                  perTryTimeout: 10s
              - match:
                - uri:
                    prefix: /api/v1/agents
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
              - match:
                - uri:
                    prefix: /api/v1/queues
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
              - match:
                - uri:
                    prefix: /api/v1/conferences
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
              - match:
                - uri:
                    prefix: /api/v1/recordings
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
              - match:
                - uri:
                    prefix: /api/v1/ivr
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
              - match:
                - uri:
                    prefix: /api/v1/supervisor
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
              # WebSocket for real-time events
              - match:
                - uri:
                    prefix: /ws
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
                timeout: 3600s
              # Health & metrics (internal)
              - match:
                - uri:
                    prefix: /actuator
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
              # Default catch-all for /api
              - match:
                - uri:
                    prefix: /api
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
            """,
                APP_NAME, namespace, APP_NAME, tenant.getSlug(),
                host,
                APP_NAME, APP_NAME, APP_NAME, APP_NAME, APP_NAME,
                APP_NAME, APP_NAME, APP_NAME, APP_NAME, APP_NAME
        );

        k8sClient.load(new java.io.ByteArrayInputStream(vsYaml.getBytes()))
                .inNamespace(namespace)
                .createOrReplace();

        log.info("Created VirtualService for PBX-Core: https://{}", host);
    }

    @Override
    public void delete(String namespace) {
        log.info("Deleting PBX-Core resources in namespace {}", namespace);

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

            log.info("Deleted PBX-Core resources in namespace {}", namespace);
        } catch (Exception e) {
            log.warn("Error deleting PBX-Core resources: {}", e.getMessage());
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
            log.warn("Error checking PBX-Core health: {}", e.getMessage());
            return false;
        }
    }
}
