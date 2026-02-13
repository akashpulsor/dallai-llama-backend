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
 * Kubernetes deployer for AI Service.
 *
 * AI Service handles:
 * - Real-time speech-to-text transcription
 * - Sentiment analysis during calls
 * - Agent assist suggestions
 * - Noise cancellation
 * - Call summarization
 * - Intent detection for IVR
 * - Quality scoring automation
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiServiceK8sDeployer implements K8sDeployer {

    private final KubernetesClient k8sClient;

    @Value("${telecom.images.ai-service:dalaillama/ai-service:latest}")
    private String aiServiceImage;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @Value("${keycloak.base-url:https://auth.dalaillama.in}")
    private String keycloakBaseUrl;

    private static final String APP_NAME = "ai-service";

    @Override
    public String name() {
        return APP_NAME;
    }

    @Override
    public void deploy(String namespace, Tenant tenant, TelecomStackConfig config) {
        log.info("Deploying AI Service for tenant {} in namespace {}", tenant.getSlug(), namespace);

        try {
            // 1. Create ConfigMap with application config
            createConfigMap(namespace, tenant, config);

            // 2. Create Deployment
            createDeployment(namespace, tenant, config);

            // 3. Create ClusterIP Service
            createService(namespace, tenant);

            // 4. Create Istio VirtualService for routing
            createVirtualService(namespace, tenant);

            log.info("AI Service deployed successfully for tenant {}", tenant.getSlug());

        } catch (Exception e) {
            log.error("Failed to deploy AI Service for tenant {}: {}", tenant.getSlug(), e.getMessage(), e);
            throw new RuntimeException("AI Service deployment failed: " + e.getMessage(), e);
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
        log.debug("Created ConfigMap for AI Service");
    }

    private String buildApplicationConfig(String namespace, Tenant tenant, TelecomStackConfig config) {
        String pbxCoreUrl = config.getPbxCoreUrl() != null
                ? config.getPbxCoreUrl()
                : "http://pbx-core." + namespace + ".svc.cluster.local:8080";

        String aiServiceUrl = config.getAiServiceUrl() != null
                ? config.getAiServiceUrl()
                : "http://ai-service." + namespace + ".svc.cluster.local:8080";

        return String.format("""
            spring:
              application:
                name: ai-service
              kafka:
                bootstrap-servers: %s
                producer:
                  key-serializer: org.apache.kafka.common.serialization.StringSerializer
                  value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
                consumer:
                  group-id: ai-service-%s
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

            # AI Feature Flags
            ai:
              features:
                transcription:
                  enabled: %s
                  language: %s
                  model: whisper-large-v3
                  real-time: true
                  word-timestamps: true
                sentiment:
                  enabled: %s
                  model: distilbert-sentiment
                  threshold: 0.7
                  alert-on-negative: true
                agent-assist:
                  enabled: %s
                  model: gpt-4-turbo
                  max-suggestions: 3
                  confidence-threshold: 0.8
                noise-cancellation:
                  enabled: %s
                  model: rnnoise
                  aggressiveness: 2
                summarization:
                  enabled: %s
                  model: gpt-4-turbo
                  max-length: 500
                intent-detection:
                  enabled: %s
                  model: distilbert-intent
                  categories:
                    - billing
                    - support
                    - sales
                    - complaint
                    - general
                quality-scoring:
                  enabled: %s
                  auto-score: true
                  criteria:
                    - greeting
                    - empathy
                    - resolution
                    - closing

            # Model Configuration
            models:
              whisper:
                endpoint: ${WHISPER_ENDPOINT:http://whisper:8000}
                timeout: 30000
              llm:
                provider: ${LLM_PROVIDER:openai}
                endpoint: ${LLM_ENDPOINT:https://api.openai.com/v1}
                api-key: ${LLM_API_KEY:}
                model: ${LLM_MODEL:gpt-4-turbo}
                max-tokens: 1000
                temperature: 0.7

            # Audio Processing
            audio:
              sample-rate: 16000
              channels: 1
              format: pcm_s16le
              chunk-duration-ms: 100
              buffer-size: 4096

            # gRPC Server (for real-time streaming)
            grpc:
              server:
                port: 9000
                max-inbound-message-size: 10485760

            # Kafka Topics
            kafka:
              topics:
                audio-stream: %s-audio-stream
                transcription-results: %s-transcription-results
                sentiment-results: %s-sentiment-results
                agent-assist: %s-agent-assist
                ai-events: %s-ai-events

            # Rate Limiting
            rate-limit:
              transcription-minutes-per-month: %d
              requests-per-minute: 100

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
                // AI Features
                config.isEnableAiTranscription(),
                config.getIvrDefaultLanguage() != null ? config.getIvrDefaultLanguage() : "en-IN",
                config.isEnableAiSentiment(),
                config.isEnableAiAgentAssist(),
                config.isEnableAiNoiseCancellation(),
                config.isEnableAiTranscription(), // summarization follows transcription
                config.isEnableAiRouting(), // intent detection for routing
                config.isEnableAiTranscription(), // quality scoring follows transcription
                // Kafka Topics
                tenant.getSlug(), tenant.getSlug(), tenant.getSlug(), tenant.getSlug(), tenant.getSlug(),
                // Rate Limit
                config.getAiMinutesPerMonth() > 0 ? config.getAiMinutesPerMonth() : 10000
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
                        "app.kubernetes.io/component", "ai",
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
                .withImage(aiServiceImage)
                .withImagePullPolicy("IfNotPresent")
                // HTTP port
                .addNewPort()
                .withName("http")
                .withContainerPort(8080)
                .withProtocol("TCP")
                .endPort()
                // gRPC port for streaming
                .addNewPort()
                .withName("grpc")
                .withContainerPort(9000)
                .withProtocol("TCP")
                .endPort()
                // Environment Variables
                .addNewEnv().withName("SPRING_CONFIG_LOCATION").withValue("/config/application.yaml").endEnv()
                .addNewEnv().withName("TENANT_ID").withValue(config.getTenantId()).endEnv()
                .addNewEnv().withName("TENANT_SLUG").withValue(config.getTenantSlug()).endEnv()
                .addNewEnv().withName("JAVA_OPTS").withValue("-Xms256m -Xmx768m").endEnv()
                // LLM API Key from Secret (if exists)
                .addNewEnv()
                .withName("LLM_API_KEY")
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName("ai-secrets")
                .withKey("llm-api-key")
                .withOptional(true)
                .endSecretKeyRef()
                .endValueFrom()
                .endEnv()
                // Volume Mounts
                .addNewVolumeMount()
                .withName("config")
                .withMountPath("/config")
                .withReadOnly(true)
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("models-cache")
                .withMountPath("/models")
                .endVolumeMount()
                // Resources (AI needs more resources)
                .withNewResources()
                .addToRequests("cpu", new Quantity("200m"))
                .addToRequests("memory", new Quantity("512Mi"))
                .addToLimits("cpu", new Quantity("2000m"))
                .addToLimits("memory", new Quantity("2Gi"))
                .endResources()
                // Probes
                .withNewLivenessProbe()
                .withNewHttpGet()
                .withPath("/actuator/health/liveness")
                .withNewPort(8080)
                .endHttpGet()
                .withInitialDelaySeconds(90)
                .withPeriodSeconds(30)
                .withTimeoutSeconds(10)
                .withFailureThreshold(3)
                .endLivenessProbe()
                .withNewReadinessProbe()
                .withNewHttpGet()
                .withPath("/actuator/health/readiness")
                .withNewPort(8080)
                .endHttpGet()
                .withInitialDelaySeconds(60)
                .withPeriodSeconds(10)
                .withTimeoutSeconds(5)
                .withFailureThreshold(3)
                .endReadinessProbe()
                .withNewStartupProbe()
                .withNewHttpGet()
                .withPath("/actuator/health")
                .withNewPort(8080)
                .endHttpGet()
                .withInitialDelaySeconds(30)
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
                .withName("models-cache")
                .withNewEmptyDir()
                .withMedium("Memory")
                .withSizeLimit(new Quantity("1Gi"))
                .endEmptyDir()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).createOrReplace();
        log.debug("Created Deployment for AI Service");
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
                // HTTP port
                .addNewPort()
                .withName("http")
                .withPort(8080)
                .withTargetPort(new IntOrString(8080))
                .withProtocol("TCP")
                .endPort()
                // gRPC port
                .addNewPort()
                .withName("grpc")
                .withPort(9000)
                .withTargetPort(new IntOrString(9000))
                .withProtocol("TCP")
                .endPort()
                .endSpec()
                .build();

        k8sClient.services().inNamespace(namespace).resource(svc).createOrReplace();
        log.debug("Created Service for AI Service");
    }

    /**
     * Create Istio VirtualService for AI API routing
     * Routes: https://api.{tenant}.{baseDomain}/ai/* -> ai-service:8080
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
              # Transcription API
              - match:
                - uri:
                    prefix: /ai/api/v1/transcription
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
                timeout: 60s
              # Sentiment Analysis API
              - match:
                - uri:
                    prefix: /ai/api/v1/sentiment
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
                timeout: 30s
              # Agent Assist API
              - match:
                - uri:
                    prefix: /ai/api/v1/assist
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
                timeout: 30s
              # Call Summarization API
              - match:
                - uri:
                    prefix: /ai/api/v1/summarize
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
                timeout: 60s
              # Intent Detection API
              - match:
                - uri:
                    prefix: /ai/api/v1/intent
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
                timeout: 30s
              # Quality Scoring API
              - match:
                - uri:
                    prefix: /ai/api/v1/quality
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
                timeout: 30s
              # Noise Cancellation API
              - match:
                - uri:
                    prefix: /ai/api/v1/noise
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
                timeout: 30s
              # WebSocket for real-time streaming
              - match:
                - uri:
                    prefix: /ai/ws
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
                timeout: 3600s
              # Health & metrics
              - match:
                - uri:
                    prefix: /ai/actuator
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
              # Default catch-all for /ai
              - match:
                - uri:
                    prefix: /ai
                route:
                - destination:
                    host: %s
                    port:
                      number: 8080
              # gRPC routes (for streaming audio)
              - match:
                - headers:
                    content-type:
                      prefix: application/grpc
                route:
                - destination:
                    host: %s
                    port:
                      number: 9000
                timeout: 3600s
            """,
                APP_NAME, namespace, APP_NAME, tenant.getSlug(),
                host,
                APP_NAME, APP_NAME, APP_NAME, APP_NAME, APP_NAME,
                APP_NAME, APP_NAME, APP_NAME, APP_NAME, APP_NAME, APP_NAME
        );

        k8sClient.load(new java.io.ByteArrayInputStream(vsYaml.getBytes()))
                .inNamespace(namespace)
                .createOrReplace();

        log.info("Created VirtualService for AI Service: https://{}/ai/*", host);
    }

    @Override
    public void delete(String namespace) {
        log.info("Deleting AI Service resources in namespace {}", namespace);

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

            log.info("Deleted AI Service resources in namespace {}", namespace);
        } catch (Exception e) {
            log.warn("Error deleting AI Service resources: {}", e.getMessage());
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
            log.warn("Error checking AI Service health: {}", e.getMessage());
            return false;
        }
    }
}