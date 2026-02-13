package com.dalai.llama.tenant.service.provisioning.telecom.k8s;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.service.provisioning.telecom.model.TelecomStackConfig;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Kubernetes deployer for Agent UI (React Dashboard).
 *
 * Deploys:
 * - ConfigMap with runtime configuration (tenant-specific settings)
 * - Nginx deployment serving React SPA
 * - ClusterIP Service
 * - Ingress with TLS for dashboard ({tenant}.{baseDomain})
 * - Istio VirtualService for SPA routing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentUiK8sDeployer implements K8sDeployer {

    private final KubernetesClient k8sClient;

    @Value("${telecom.images.agent-ui:dalaillama/agent-ui:latest}")
    private String agentUiImage;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @Value("${cluster.issuer:letsencrypt-prod}")
    private String clusterIssuer;

    @Value("${ingress.class:nginx}")
    private String ingressClass;

    @Value("${keycloak.base-url:https://auth.dalaillama.in}")
    private String keycloakBaseUrl;

    private static final String APP_NAME = "agent-ui";

    @Override
    public String name() {
        return APP_NAME;
    }

    @Override
    public void deploy(String namespace, Tenant tenant, TelecomStackConfig config) {
        log.info("Deploying Agent UI for tenant {} in namespace {}", tenant.getSlug(), namespace);

        try {
            // 1. Create ConfigMap with runtime config
            createRuntimeConfig(namespace, tenant, config);

            // 2. Create nginx config for SPA routing
            createNginxConfig(namespace, tenant);

            // 3. Deploy nginx + React SPA
            createDeployment(namespace, tenant);

            // 4. Create ClusterIP Service
            createService(namespace, tenant);

            // 5. Create Ingress with TLS (dashboard only)
            createIngress(namespace, tenant);

            // 6. Create Istio VirtualService
            createVirtualService(namespace, tenant);

            log.info("Agent UI deployed successfully for tenant {}. URL: https://{}.{}",
                    tenant.getSlug(), tenant.getSlug(), baseDomain);

        } catch (Exception e) {
            log.error("Failed to deploy Agent UI for tenant {}: {}", tenant.getSlug(), e.getMessage(), e);
            throw new RuntimeException("Agent UI deployment failed: " + e.getMessage(), e);
        }
    }

    private void createRuntimeConfig(String namespace, Tenant tenant, TelecomStackConfig config) {
        String tenantHost = tenant.getSlug() + "." + baseDomain;
        String apiHost = "api." + tenant.getSlug() + "." + baseDomain;
        String wssHost = tenant.getSlug() + ".wss." + baseDomain;

        String runtimeJs = String.format("""
            window.__RUNTIME_CONFIG__ = {
              // Tenant Info
              TENANT_ID: "%s",
              TENANT_SLUG: "%s",
              TENANT_NAME: "%s",
              
              // API Endpoints
              API_BASE_URL: "https://%s",
              PBX_CORE_URL: "https://%s/api/v1",
              AGENT_SERVICE_URL: "https://%s/agent/api/v1",
              AI_SERVICE_URL: "https://%s/ai/api/v1",
              
              // WebSocket/WebRTC
              WS_URL: "wss://%s",
              SIP_WSS_URL: "wss://%s:8089",
              
              // SIP Configuration
              SIP_SERVER: "%s",
              SIP_PORT: 5060,
              SIP_TLS_PORT: 5061,
              SIP_DOMAIN: "%s",
              
              // TURN/STUN
              TURN_SERVER: "%s",
              TURN_USERNAME: "%s",
              TURN_CREDENTIAL: "%s",
              STUN_SERVER: "stun:%s:3478",
              
              // Keycloak SSO
              KEYCLOAK_URL: "%s",
              KEYCLOAK_REALM: "tenant-%s",
              KEYCLOAK_CLIENT_ID: "dalaillama-%s",
              
              // Feature Flags
              FEATURES: {
                AI_TRANSCRIPTION: %s,
                AI_SENTIMENT: %s,
                AI_AGENT_ASSIST: %s,
                AI_NOISE_CANCELLATION: %s,
                WHISPER: %s,
                BARGE: %s,
                SILENT_MONITOR: %s,
                TAKEOVER: %s,
                CONFERENCE: %s,
                RECORDING: %s,
                VOICEMAIL: %s,
                CALLBACK_QUEUE: %s,
                SKILLS_ROUTING: %s,
                OUTBOUND_DIALER: %s,
                SCREEN_POP: %s,
                OMNICHANNEL: %s
              },
              
              // Limits
              LIMITS: {
                MAX_AGENTS: %d,
                MAX_CONCURRENT_CALLS: %d,
                MAX_QUEUES: %d,
                MAX_CONFERENCE_PARTICIPANTS: %d
              },
              
              // UI Settings
              DEFAULT_LANGUAGE: "%s",
              TIMEZONE: "%s",
              DATE_FORMAT: "YYYY-MM-DD",
              TIME_FORMAT: "HH:mm:ss"
            };
            """,
                // Tenant Info
                config.getTenantId(),
                config.getTenantSlug(),
                tenant.getCompanyName() != null ? escapeJs(tenant.getCompanyName()) : config.getTenantSlug(),

                // API Endpoints
                apiHost,
                apiHost,
                apiHost,
                apiHost,

                // WebSocket
                wssHost,
                wssHost,

                // SIP
                //tenant.getSipExternalIp() != null ? tenant.getSipExternalIp() : "",
                config.getRealm(),

                // TURN
                //tenant.getTurnUrl() != null ? tenant.getTurnUrl() : "turn:" + (tenant.getSipExternalIp() != null ? tenant.getSipExternalIp() : "") + ":3478",
                config.getTenantSlug(),
                config.getTurnSecret() != null ? config.getTurnSecret() : "",
                ///tenant.getSipExternalIp() != null ? tenant.getSipExternalIp() : "",

                // Keycloak
                keycloakBaseUrl,
                tenant.getSlug(),
                tenant.getSlug(),

                // Feature Flags
                config.isEnableAiTranscription(),
                config.isEnableAiSentiment(),
                config.isEnableAiAgentAssist(),
                config.isEnableAiNoiseCancellation(),
                config.isEnableWhisper(),
                config.isEnableBarge(),
                config.isEnableSilentMonitor(),
                config.isEnableTakeover(),
                config.isEnableConference(),
                config.isEnableRecording(),
                config.isEnableVoicemail(),
                config.isEnableCallbackQueue(),
                config.isEnableSkillsRouting(),
                config.isEnableOutboundDialer(),
                config.isEnableScreenPop(),
                config.isEnableOmnichannel(),

                // Limits
                config.getMaxAgents(),
                config.getMaxConcurrentCalls(),
                config.getMaxQueues(),
                config.getMaxConferenceParticipants(),

                // UI Settings
                config.getIvrDefaultLanguage() != null ? config.getIvrDefaultLanguage() : "en-IN",
                tenant.getTimezone() != null ? tenant.getTimezone() : "Asia/Kolkata"
        );

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
                .withData(Map.of("runtime-config.js", runtimeJs))
                .build();

        k8sClient.configMaps().inNamespace(namespace).resource(cm).createOrReplace();
        log.debug("Created runtime config ConfigMap for tenant {}", tenant.getSlug());
    }

    private void createNginxConfig(String namespace, Tenant tenant) {
        String nginxConf = """
            server {
                listen 80;
                server_name _;
                root /usr/share/nginx/html;
                index index.html;
                
                # Gzip compression
                gzip on;
                gzip_types text/plain text/css application/json application/javascript text/xml application/xml;
                gzip_min_length 1000;
                
                # Security headers
                add_header X-Frame-Options "SAMEORIGIN" always;
                add_header X-Content-Type-Options "nosniff" always;
                add_header X-XSS-Protection "1; mode=block" always;
                add_header Referrer-Policy "strict-origin-when-cross-origin" always;
                
                # Cache static assets
                location ~* \\.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
                    expires 1y;
                    add_header Cache-Control "public, immutable";
                }
                
                # Runtime config - no cache
                location /config/ {
                    expires -1;
                    add_header Cache-Control "no-store, no-cache, must-revalidate";
                }
                
                # Health check endpoint
                location /health {
                    access_log off;
                    return 200 "healthy";
                    add_header Content-Type text/plain;
                }
                
                # SPA fallback - all routes go to index.html
                location / {
                    try_files $uri $uri/ /index.html;
                }
            }
            """;

        ConfigMap cm = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(APP_NAME + "-nginx-config")
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "app", APP_NAME,
                        "tenant", tenant.getSlug()
                ))
                .endMetadata()
                .withData(Map.of("default.conf", nginxConf))
                .build();

        k8sClient.configMaps().inNamespace(namespace).resource(cm).createOrReplace();
        log.debug("Created nginx config for tenant {}", tenant.getSlug());
    }

    private void createDeployment(String namespace, Tenant tenant) {
        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName(APP_NAME)
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "app", APP_NAME,
                        "tenant", tenant.getSlug(),
                        "version", "v1",
                        "app.kubernetes.io/name", APP_NAME,
                        "app.kubernetes.io/component", "frontend",
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
                        "sidecar.istio.io/inject", "true"
                ))
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName(APP_NAME)
                .withImage(agentUiImage)
                .withImagePullPolicy("IfNotPresent")
                .addNewPort()
                .withName("http")
                .withContainerPort(80)
                .withProtocol("TCP")
                .endPort()
                .addNewVolumeMount()
                .withName("runtime-config")
                .withMountPath("/usr/share/nginx/html/config")
                .withReadOnly(true)
                .endVolumeMount()
                .addNewVolumeMount()
                .withName("nginx-config")
                .withMountPath("/etc/nginx/conf.d")
                .withReadOnly(true)
                .endVolumeMount()
                .withNewResources()
                .addToRequests("cpu", new Quantity("50m"))
                .addToRequests("memory", new Quantity("64Mi"))
                .addToLimits("cpu", new Quantity("200m"))
                .addToLimits("memory", new Quantity("128Mi"))
                .endResources()
                .withNewLivenessProbe()
                .withNewHttpGet()
                .withPath("/health")
                .withNewPort(80)
                .endHttpGet()
                .withInitialDelaySeconds(10)
                .withPeriodSeconds(30)
                .withTimeoutSeconds(5)
                .withFailureThreshold(3)
                .endLivenessProbe()
                .withNewReadinessProbe()
                .withNewHttpGet()
                .withPath("/health")
                .withNewPort(80)
                .endHttpGet()
                .withInitialDelaySeconds(5)
                .withPeriodSeconds(10)
                .withTimeoutSeconds(3)
                .withFailureThreshold(3)
                .endReadinessProbe()
                .endContainer()
                .addNewVolume()
                .withName("runtime-config")
                .withNewConfigMap()
                .withName(APP_NAME + "-config")
                .endConfigMap()
                .endVolume()
                .addNewVolume()
                .withName("nginx-config")
                .withNewConfigMap()
                .withName(APP_NAME + "-nginx-config")
                .endConfigMap()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        k8sClient.apps().deployments().inNamespace(namespace).resource(deployment).createOrReplace();
        log.debug("Created Deployment for Agent UI");
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
                .withPort(80)
                .withTargetPort(new IntOrString(80))
                .withProtocol("TCP")
                .endPort()
                .endSpec()
                .build();

        k8sClient.services().inNamespace(namespace).resource(svc).createOrReplace();
        log.debug("Created Service for Agent UI");
    }

    /**
     * Create Ingress for Agent UI dashboard only
     * URL: https://{tenant}.{baseDomain}
     */
    private void createIngress(String namespace, Tenant tenant) {
        String host = tenant.getSlug() + "." + baseDomain;
        String tlsSecretName = tenant.getSlug() + "-ui-tls";

        Ingress ingress = new IngressBuilder()
                .withNewMetadata()
                .withName(APP_NAME + "-ingress")
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "app", APP_NAME,
                        "tenant", tenant.getSlug()
                ))
                .withAnnotations(Map.of(
                        "kubernetes.io/ingress.class", ingressClass,
                        "cert-manager.io/cluster-issuer", clusterIssuer,
                        "nginx.ingress.kubernetes.io/ssl-redirect", "true",
                        "nginx.ingress.kubernetes.io/proxy-body-size", "10m",
                        "nginx.ingress.kubernetes.io/proxy-read-timeout", "3600",
                        "nginx.ingress.kubernetes.io/proxy-send-timeout", "3600"
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
                .withName(APP_NAME)
                .withNewPort().withNumber(80).endPort()
                .endService()
                .endBackend()
                .endPath()
                .endHttp()
                .endRule()
                .endSpec()
                .build();

        k8sClient.network().v1().ingresses().inNamespace(namespace).resource(ingress).createOrReplace();
        log.info("Created Ingress for https://{}", host);
    }

    /**
     * Create Istio VirtualService for Agent UI
     * Routes: https://{tenant}.{baseDomain}/* -> agent-ui:80
     */
    private void createVirtualService(String namespace, Tenant tenant) {
        String host = tenant.getSlug() + "." + baseDomain;

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
              # Static assets with caching
              - match:
                - uri:
                    regex: ".*\\\\.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$"
                route:
                - destination:
                    host: %s
                    port:
                      number: 80
                headers:
                  response:
                    set:
                      cache-control: "public, max-age=31536000, immutable"
              # Runtime config - no cache
              - match:
                - uri:
                    prefix: /config
                route:
                - destination:
                    host: %s
                    port:
                      number: 80
                headers:
                  response:
                    set:
                      cache-control: "no-store, no-cache, must-revalidate"
              # Health check
              - match:
                - uri:
                    exact: /health
                route:
                - destination:
                    host: %s
                    port:
                      number: 80
              # Default - SPA routing
              - match:
                - uri:
                    prefix: /
                route:
                - destination:
                    host: %s
                    port:
                      number: 80
            """,
                APP_NAME, namespace, APP_NAME, tenant.getSlug(),
                host,
                APP_NAME, APP_NAME, APP_NAME, APP_NAME
        );

        k8sClient.load(new java.io.ByteArrayInputStream(vsYaml.getBytes()))
                .inNamespace(namespace)
                .createOrReplace();

        log.info("Created VirtualService for Agent UI: https://{}", host);
    }

    @Override
    public void delete(String namespace) {
        log.info("Deleting Agent UI resources in namespace {}", namespace);

        try {
            // Delete VirtualService
            k8sClient.genericKubernetesResources("networking.istio.io/v1beta1", "VirtualService")
                    .inNamespace(namespace)
                    .withName(APP_NAME + "-vs")
                    .delete();

            // Delete Ingress
            k8sClient.network().v1().ingresses().inNamespace(namespace).withName(APP_NAME + "-ingress").delete();

            // Delete Service
            k8sClient.services().inNamespace(namespace).withName(APP_NAME).delete();

            // Delete Deployment
            k8sClient.apps().deployments().inNamespace(namespace).withName(APP_NAME).delete();

            // Delete ConfigMaps
            k8sClient.configMaps().inNamespace(namespace).withName(APP_NAME + "-config").delete();
            k8sClient.configMaps().inNamespace(namespace).withName(APP_NAME + "-nginx-config").delete();

            log.info("Deleted Agent UI resources in namespace {}", namespace);
        } catch (Exception e) {
            log.warn("Error deleting Agent UI resources: {}", e.getMessage());
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
            log.warn("Error checking Agent UI health: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Update runtime configuration without redeploying
     */
    public void updateRuntimeConfig(String namespace, Tenant tenant, TelecomStackConfig config) {
        log.info("Updating Agent UI runtime config for tenant {}", tenant.getSlug());
        createRuntimeConfig(namespace, tenant, config);
        restartDeployment(namespace);
    }

    /**
     * Restart Agent UI deployment (rolling restart)
     */
    public void restartDeployment(String namespace) {
        k8sClient.apps().deployments()
                .inNamespace(namespace)
                .withName(APP_NAME)
                .rolling()
                .restart();
        log.debug("Triggered rolling restart for Agent UI in namespace {}", namespace);
    }

    private String escapeJs(String input) {
        if (input == null) return "";
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}