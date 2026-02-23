package com.dalai.llama.tenant.service.impl;


import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Istio Gateway Configuration Service
 *
 * Creates Istio resources for external access:
 * - Gateway: TLS termination for tenant domains
 * - VirtualService: Routing to frontend services
 * - DestinationRule: Load balancing policies
 *
 * URL Structure:
 * - https://app.{tenant}.dalaillama.in → agent-ui
 * - https://admin.{tenant}.dalaillama.in → admin-ui
 * - https://supervisor.{tenant}.dalaillama.in → supervisor-ui
 * - https://ivr.{tenant}.dalaillama.in → ivr-builder
 *
 * Each frontend uses Keycloak OIDC with:
 * - Issuer: https://auth.dalaillama.in/realms/{tenant}
 * - Client: dalaillama-{tenant} or dalaillama-{tenant}-{app}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IstioGatewayConfigService {

    private final KubernetesClient k8sClient;
    private final ObjectMapper objectMapper;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @Value("${dalaillama.istio.gateway:dalaillama-gateway}")
    private String gatewayName;

    @Value("${dalaillama.istio.namespace:istio-system}")
    private String istioNamespace;

    private static final CustomResourceDefinitionContext VIRTUAL_SERVICE_CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup("networking.istio.io")
            .withVersion("v1beta1")
            .withPlural("virtualservices")
            .withScope("Namespaced")
            .build();

    private static final CustomResourceDefinitionContext GATEWAY_CRD = new CustomResourceDefinitionContext.Builder()
            .withGroup("networking.istio.io")
            .withVersion("v1beta1")
            .withPlural("gateways")
            .withScope("Namespaced")
            .build();

    /**
     * Create VirtualService for tenant apps
     */
    public void createVirtualServiceForTenant(TenantApp app) {
        String tenantSlug = app.getNamespace();
        String targetNamespace = Boolean.TRUE.equals(app.getDedicatedInfrastructure())
                ? tenantSlug : "dalaillama";

        log.info("Creating VirtualService for tenant {} in namespace {}", tenantSlug, targetNamespace);

        // Parse app panels to get app configurations
        List<AppPanel> apps = parseAppPanels(app.getAppPanels());

        if (apps.isEmpty()) {
            log.warn("No app panels found for tenant {}", tenantSlug);
            return;
        }

        // Create VirtualService for each app
        for (AppPanel panel : apps) {
            String host = panel.subdomain + "." + tenantSlug + "." + baseDomain;
            String serviceName = getServiceName(panel.appType, tenantSlug);

            Map<String, Object> virtualService = buildVirtualService(
                    tenantSlug + "-" + panel.subdomain,
                    targetNamespace,
                    host,
                    serviceName,
                    80
            );

            applyVirtualService(targetNamespace, virtualService);
            log.info("Created VirtualService: {} -> {}", host, serviceName);
        }

        // Create VirtualService for WebSocket (SIP over WebSocket for WebRTC)
        createWebSocketVirtualService(tenantSlug, targetNamespace);
    }

    private Map<String, Object> buildVirtualService(String name, String namespace,
                                                    String host, String service, int port) {
        return Map.of(
                "apiVersion", "networking.istio.io/v1beta1",
                "kind", "VirtualService",
                "metadata", Map.of(
                        "name", name,
                        "namespace", namespace,
                        "labels", Map.of(
                                "app.kubernetes.io/managed-by", "dalaillama"
                        )
                ),
                "spec", Map.of(
                        "hosts", List.of(host),
                        "gateways", List.of(istioNamespace + "/" + gatewayName),
                        "http", List.of(Map.of(
                                "match", List.of(Map.of(
                                        "uri", Map.of("prefix", "/")
                                )),
                                "route", List.of(Map.of(
                                        "destination", Map.of(
                                                "host", service + "." + namespace + ".svc.cluster.local",
                                                "port", Map.of("number", port)
                                        )
                                )),
                                "corsPolicy", Map.of(
                                        "allowOrigins", List.of(Map.of("regex", ".*")),
                                        "allowMethods", List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"),
                                        "allowHeaders", List.of("*"),
                                        "allowCredentials", true
                                )
                        ))
                )
        );
    }

    private void createWebSocketVirtualService(String tenantSlug, String namespace) {
        String host = "ws." + tenantSlug + "." + baseDomain;
        String service = "kamailio";

        Map<String, Object> virtualService = Map.of(
                "apiVersion", "networking.istio.io/v1beta1",
                "kind", "VirtualService",
                "metadata", Map.of(
                        "name", tenantSlug + "-websocket",
                        "namespace", namespace
                ),
                "spec", Map.of(
                        "hosts", List.of(host),
                        "gateways", List.of(istioNamespace + "/" + gatewayName),
                        "http", List.of(Map.of(
                                "match", List.of(Map.of(
                                        "uri", Map.of("prefix", "/ws")
                                )),
                                "route", List.of(Map.of(
                                        "destination", Map.of(
                                                "host", service + "." + namespace + ".svc.cluster.local",
                                                "port", Map.of("number", 8443)
                                        )
                                )),
                                "websocketUpgrade", true
                        ))
                )
        );

        applyVirtualService(namespace, virtualService);
        log.info("Created WebSocket VirtualService: {} -> {}:8443", host, service);
    }

    /**
     * Create shared Gateway for all tenants (run once during platform setup)
     */
    public void createSharedGateway() {
        Map<String, Object> gateway = Map.of(
                "apiVersion", "networking.istio.io/v1beta1",
                "kind", "Gateway",
                "metadata", Map.of(
                        "name", gatewayName,
                        "namespace", istioNamespace
                ),
                "spec", Map.of(
                        "selector", Map.of(
                                "istio", "ingressgateway"
                        ),
                        "servers", List.of(
                                // HTTPS for all tenant subdomains
                                Map.of(
                                        "port", Map.of(
                                                "number", 443,
                                                "name", "https",
                                                "protocol", "HTTPS"
                                        ),
                                        "hosts", List.of("*." + baseDomain),
                                        "tls", Map.of(
                                                "mode", "SIMPLE",
                                                "credentialName", "dalaillama-tls"
                                        )
                                ),
                                // HTTP redirect to HTTPS
                                Map.of(
                                        "port", Map.of(
                                                "number", 80,
                                                "name", "http",
                                                "protocol", "HTTP"
                                        ),
                                        "hosts", List.of("*." + baseDomain),
                                        "tls", Map.of(
                                                "httpsRedirect", true
                                        )
                                )
                        )
                )
        );

        try {
            k8sClient.genericKubernetesResources(GATEWAY_CRD)
                    .inNamespace(istioNamespace)
                    .resource(objectMapper.convertValue(gateway, io.fabric8.kubernetes.api.model.GenericKubernetesResource.class))
                    .serverSideApply();
            log.info("Created shared Gateway: {}", gatewayName);
        } catch (Exception e) {
            log.error("Failed to create Gateway: {}", e.getMessage());
        }
    }

    private void applyVirtualService(String namespace, Map<String, Object> vs) {
        try {
            k8sClient.genericKubernetesResources(VIRTUAL_SERVICE_CRD)
                    .inNamespace(namespace)
                    .resource(objectMapper.convertValue(vs, io.fabric8.kubernetes.api.model.GenericKubernetesResource.class))
                    .serverSideApply();
        } catch (Exception e) {
            log.error("Failed to apply VirtualService: {}", e.getMessage());
        }
    }

    private String getServiceName(String appType, String tenantSlug) {
        return switch (appType) {
            case "CONTACT_CENTER" -> "agent-ui";
            case "IVR_BUILDER" -> "ivr-builder";
            case "ADMIN_PANEL" -> "admin-ui";
            case "SUPERVISOR" -> "supervisor-ui";
            case "WALLBOARD" -> "wallboard-ui";
            case "REPORTING" -> "reporting-ui";
            default -> "agent-ui";
        };
    }

    private List<AppPanel> parseAppPanels(String appPanelsJson) {
        if (appPanelsJson == null || appPanelsJson.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.asList(objectMapper.readValue(appPanelsJson, AppPanel[].class));
        } catch (Exception e) {
            log.error("Failed to parse app panels: {}", e.getMessage());
            return List.of();
        }
    }

    public void deleteVirtualServicesForTenant(String tenantSlug, String namespace) {
        try {
            k8sClient.genericKubernetesResources(VIRTUAL_SERVICE_CRD)
                    .inNamespace(namespace)
                    .withLabel("app.kubernetes.io/managed-by", "dalaillama")
                    .delete();
            log.info("Deleted VirtualServices for tenant {}", tenantSlug);
        } catch (Exception e) {
            log.error("Failed to delete VirtualServices: {}", e.getMessage());
        }
    }

    record AppPanel(String appType, String displayName, String subdomain, String url,
                    String icon, int displayOrder, String keycloakClientId,
                    String frontendImage, String requiredRoles) {}
}