package com.dalai.llama.tenant.service.provisioning.steps;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.exception.ProvisioningException;
import com.dalai.llama.tenant.service.impl.TenantAppService;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoadBalancerProvisioner {

    private final KubernetesClient kubernetesClient;
    private final TenantAppService tenantAppService;

    @Value("${dalai.keycloak.base-url:https://auth.dalaillama.in}")
    private String keycloakBaseUrl;

    @Value("${dalai.gateway.ref:istio-system/central-gateway}")
    private String gatewayRef;

    @Value("${dalai.base-domain:dalaillama.in}")
    private String baseDomain;

    // Istio CRD contexts
    private static final CustomResourceDefinitionContext VIRTUAL_SERVICE_CTX =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("networking.istio.io")
                    .withVersion("v1")
                    .withPlural("virtualservices")
                    .withScope("Namespaced")
                    .build();

    private static final CustomResourceDefinitionContext REQUEST_AUTH_CTX =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("security.istio.io")
                    .withVersion("v1")
                    .withPlural("requestauthentications")
                    .withScope("Namespaced")
                    .build();

    private static final CustomResourceDefinitionContext AUTH_POLICY_CTX =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("security.istio.io")
                    .withVersion("v1")
                    .withPlural("authorizationpolicies")
                    .withScope("Namespaced")
                    .build();

    /**
     * Execute load balancer provisioning for a tenant.
     *
     * @param tenant      The tenant being provisioned
     * @param productCode The product code to determine which apps to create
     * @return Result map with created resources info
     */
    public Map<String, Object> execute(Tenant tenant, String productCode) {
        log.info("Creating load balancer for tenant {} with product {}", tenant.getId(), productCode);

        // Validate prerequisites
        validatePrerequisites(tenant);

        try {
            String namespace = resolveNamespace(tenant);
            String slug = tenant.getSlug();

            // 1. Create tenant apps from product (if not already created)
            List<TenantApp> apps = tenantAppService.getEnabledApps(tenant.getId());
            if (apps.isEmpty()) {
                log.info("Creating apps for tenant {} from product {}", tenant.getId(), productCode);
                apps = tenantAppService.createAppsFromProduct(tenant, productCode);
            }

            if (apps.isEmpty()) {
                throw new ProvisioningException("No apps available for product: " + productCode);
            }

            // 2. Collect all domains
            List<String> allDomains = tenantAppService.collectAllDomains(tenant);

            // 3. Create VirtualService
            GenericKubernetesResource vs = buildVirtualService(tenant, apps, allDomains, namespace);
            applyResource(VIRTUAL_SERVICE_CTX, vs, namespace);
            log.info("Created VirtualService: vs-{}", slug);

            // 4. Create RequestAuthentication
            GenericKubernetesResource ra = buildRequestAuthentication(tenant, apps, namespace);
            applyResource(REQUEST_AUTH_CTX, ra, namespace);
            log.info("Created RequestAuthentication: ra-{}", slug);

            // 5. Create AuthorizationPolicy
            GenericKubernetesResource ap = buildAuthorizationPolicy(tenant, namespace);
            applyResource(AUTH_POLICY_CTX, ap, namespace);
            log.info("Created AuthorizationPolicy: ap-{}", slug);

            // 6. Create ConfigMaps for each app
            for (TenantApp app : apps) {
                createAppConfigMap(tenant, app, namespace);
            }

            // 7. Update tenant URLs
            String primarySubdomain = apps.stream()
                    .filter(a -> a.getDisplayOrder() == 0)
                    .findFirst()
                    .map(TenantApp::getSubdomain)
                    .orElse("app");

            //tenant.setDashboardUrl("https://" + primarySubdomain + "." + slug + "." + baseDomain);
            //tenant.setWebsocketUrl("wss://ws." + slug + "." + baseDomain);

            // Build result
            Map<String, String> appUrls = apps.stream()
                    .collect(Collectors.toMap(
                            a -> a.getAppType().name(),
                            a -> a.getFullUrl(slug)
                    ));

            log.info("Load balancer created for tenant {} with {} apps", tenant.getId(), apps.size());

            return Map.of(
                    "status", "SUCCESS",
                    "namespace", namespace,
                    "productCode", productCode,
                    "domains", allDomains,
                    "apps", appUrls,
                    "appsCount", apps.size()
                    //"dashboardUrl", tenant.getDashboardUrl(),
                    //"websocketUrl", tenant.getWebsocketUrl()
            );

        } catch (Exception e) {
            log.error("Failed to create load balancer for tenant {}: {}", tenant.getId(), e.getMessage(), e);
            throw new ProvisioningException("Load balancer creation failed: " + e.getMessage(), e);
        }
    }

    private void validatePrerequisites(Tenant tenant) {
       /* if (tenant.getKeycloakClientId() == null || tenant.getKeycloakClientId().isBlank()) {
            throw new ProvisioningException(
                    "Tenant's keycloakClientId is not set. Run executeKeycloakClientStep first.");
        }
        if (tenant.getKeycloakRealmName() == null || tenant.getKeycloakRealmName().isBlank()) {
            throw new ProvisioningException(
                    "Tenant's keycloakRealmName is not set. Run executeKeycloakRealmStep first.");
        }*/
    }

    // ========================================================================
    // VIRTUAL SERVICE
    // ========================================================================

    private GenericKubernetesResource buildVirtualService(
            Tenant tenant, List<TenantApp> apps, List<String> domains, String namespace) {

        String slug = tenant.getSlug();
        List<Map<String, Object>> httpRoutes = new ArrayList<>();

        // CORS policy for API routes
        Map<String, Object> corsPolicy = Map.of(
                "allowOrigins", List.of(
                        Map.of("regex", "https://.*\\." + baseDomain.replace(".", "\\."))
                ),
                "allowMethods", List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"),
                "allowHeaders", List.of(
                        "authorization", "content-type", "x-requested-with",
                        "x-tenant-id", "x-correlation-id"
                ),
                "allowCredentials", true,
                "maxAge", "86400s"
        );

        // 1. Health check routes (no auth)
        httpRoutes.add(createRoute("health",
                List.of(exactMatch("/health"), exactMatch("/ready"), prefixMatch("/actuator/")),
                destination(slug + "-api-gateway", namespace, 8080),
                null));

        // 2. Auth config endpoint (no auth - frontend calls this before login)
        httpRoutes.add(createRoute("auth-config",
                List.of(prefixMatch("/api/v1/auth/")),
                destination(slug + "-api-gateway", namespace, 8080),
                corsPolicy));

        // 3. Public API routes (no auth)
        httpRoutes.add(createRoute("api-public",
                List.of(prefixMatch("/api/v1/public/")),
                destination(slug + "-api-gateway", namespace, 8080),
                corsPolicy));

        // 4. Protected API routes
        httpRoutes.add(createRoute("api",
                List.of(prefixMatch("/api/")),
                destination(slug + "-api-gateway", namespace, 8080),
                corsPolicy));

        // 5. WebSocket route
        httpRoutes.add(createWebSocketRoute("websocket",
                "/ws/",
                destination(slug + "-ai-service", namespace, 8080)));

        // 6. Static assets
        httpRoutes.add(createRoute("static",
                List.of(prefixMatch("/static/"), prefixMatch("/assets/")),
                destination(slug + "-static-assets", namespace, 80),
                null));

        // 7. Per-app frontend routes (based on Host header)
        for (TenantApp app : apps) {
            String appDomain = app.getFullDomain(slug);
            httpRoutes.add(createHostBasedRoute(
                    "frontend-" + app.getSubdomain(),
                    appDomain,
                    destination(app.getFrontendService(), namespace, app.getFrontendPort())
            ));
        }

        // 8. Default catch-all route
        String defaultFrontend = apps.stream()
                .filter(a -> a.getDisplayOrder() == 0)
                .findFirst()
                .map(TenantApp::getFrontendService)
                .orElse(slug + "-app-ui");

        httpRoutes.add(createRoute("default",
                List.of(prefixMatch("/")),
                destination(defaultFrontend, namespace, 80),
                null));

        // Build VirtualService spec
        Map<String, Object> spec = Map.of(
                "hosts", domains,
                "gateways", List.of(gatewayRef),
                "http", httpRoutes
        );

        return new GenericKubernetesResourceBuilder()
                .withApiVersion("networking.istio.io/v1")
                .withKind("VirtualService")
                .withNewMetadata()
                .withName("vs-" + slug)
                .withNamespace(namespace)
                .withLabels(buildLabels(tenant))
                .endMetadata()
                .withAdditionalProperties(Map.of("spec", spec))
                .build();
    }

    // ========================================================================
    // REQUEST AUTHENTICATION
    // ========================================================================

    private GenericKubernetesResource buildRequestAuthentication(
            Tenant tenant, List<TenantApp> apps, String namespace) {

        String slug = tenant.getSlug();
        String issuer = keycloakBaseUrl + "/realms/" + tenant.getKeycloakRealmName();
        String jwksUri = issuer + "/protocol/openid-connect/certs";

        // Collect all client IDs (audiences)
        List<String> allClientIds = apps.stream()
                .map(TenantApp::getKeycloakClientId)
                .distinct()
                .toList();

        Map<String, Object> jwtRule = new LinkedHashMap<>();
        jwtRule.put("issuer", issuer);
        jwtRule.put("jwksUri", jwksUri);
        jwtRule.put("audiences", allClientIds);
        jwtRule.put("forwardOriginalToken", true);
        jwtRule.put("fromHeaders", List.of(
                Map.of("name", "Authorization", "prefix", "Bearer ")
        ));
        jwtRule.put("outputClaimToHeaders", List.of(
                Map.of("header", "x-user-id", "claim", "sub"),
                Map.of("header", "x-user-email", "claim", "email"),
                Map.of("header", "x-user-roles", "claim", "realm_access.roles"),
                Map.of("header", "x-client-id", "claim", "azp")
        ));

        Map<String, Object> spec = Map.of(
                "selector", Map.of(
                        "matchLabels", Map.of("app", slug + "-api-gateway")
                ),
                "jwtRules", List.of(jwtRule)
        );

        return new GenericKubernetesResourceBuilder()
                .withApiVersion("security.istio.io/v1")
                .withKind("RequestAuthentication")
                .withNewMetadata()
                .withName("ra-" + slug)
                .withNamespace(namespace)
                .withLabels(buildLabels(tenant))
                .endMetadata()
                .withAdditionalProperties(Map.of("spec", spec))
                .build();
    }

    // ========================================================================
    // AUTHORIZATION POLICY
    // ========================================================================

    private GenericKubernetesResource buildAuthorizationPolicy(Tenant tenant, String namespace) {
        String slug = tenant.getSlug();
        String issuer = keycloakBaseUrl + "/realms/" + tenant.getKeycloakRealmName();

        Map<String, Object> spec = Map.of(
                "selector", Map.of(
                        "matchLabels", Map.of("app", slug + "-api-gateway")
                ),
                "action", "ALLOW",
                "rules", List.of(
                        // Rule 1: Allow public paths without auth
                        Map.of(
                                "to", List.of(Map.of(
                                        "operation", Map.of(
                                                "paths", List.of(
                                                        "/health", "/ready", "/actuator/*",
                                                        "/api/v1/auth/*",
                                                        "/api/v1/public/*"
                                                )
                                        )
                                ))
                        ),
                        // Rule 2: Allow authenticated requests to /api/*
                        Map.of(
                                "from", List.of(Map.of(
                                        "source", Map.of(
                                                "requestPrincipals", List.of(issuer + "/*")
                                        )
                                )),
                                "to", List.of(Map.of(
                                        "operation", Map.of(
                                                "paths", List.of("/api/*")
                                        )
                                ))
                        )
                )
        );

        return new GenericKubernetesResourceBuilder()
                .withApiVersion("security.istio.io/v1")
                .withKind("AuthorizationPolicy")
                .withNewMetadata()
                .withName("ap-" + slug)
                .withNamespace(namespace)
                .withLabels(buildLabels(tenant))
                .endMetadata()
                .withAdditionalProperties(Map.of("spec", spec))
                .build();
    }

    // ========================================================================
    // CONFIG MAP
    // ========================================================================

    private void createAppConfigMap(Tenant tenant, TenantApp app, String namespace) {
        String slug = tenant.getSlug();
        String configMapName = app.getFrontendService() + "-config";

        String configJson = """
                {
                    "app": {
                        "type": "%s",
                        "name": "%s",
                        "subdomain": "%s",
                        "icon": "%s"
                    },
                    "keycloak": {
                        "url": "%s",
                        "realm": "%s",
                        "clientId": "%s"
                    },
                    "api": {
                        "baseUrl": "/api/v1"
                    },
                    "tenant": {
                        "id": "%s",
                        "slug": "%s",
                        "name": "%s"
                    },
                    "websocket": {
                        "url": "wss://ws.%s.%s"
                    }
                }
                """.formatted(
                app.getAppType().name(),
                app.getDisplayName(),
                app.getSubdomain(),
                app.getIcon() != null ? app.getIcon() : "",
                keycloakBaseUrl,
                tenant.getKeycloakRealmName(),
                app.getKeycloakClientId(),
                tenant.getId().toString(),
                slug,
                tenant.getName(),
                slug,
                baseDomain
        );

        ConfigMap configMap = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(configMapName)
                .withNamespace(namespace)
                .withLabels(Map.of(
                        "app.kubernetes.io/managed-by", "dalai-provisioner",
                        "dalai.llama/tenant", slug,
                        "dalai.llama/tenant-id", tenant.getId().toString(),
                        "dalai.llama/app", app.getSubdomain(),
                        "dalai.llama/app-type", app.getAppType().name()
                ))
                .endMetadata()
                .withData(Map.of(
                        "config.json", configJson,
                        "REACT_APP_KEYCLOAK_URL", keycloakBaseUrl,
                        "REACT_APP_KEYCLOAK_REALM", tenant.getKeycloakRealmName(),
                        "REACT_APP_KEYCLOAK_CLIENT_ID", app.getKeycloakClientId(),
                        "REACT_APP_APP_TYPE", app.getAppType().name(),
                        "REACT_APP_TENANT_ID", tenant.getId().toString(),
                        "REACT_APP_TENANT_SLUG", slug
                ))
                .build();

        kubernetesClient.configMaps()
                .inNamespace(namespace)
                .resource(configMap)
                .serverSideApply();

        log.debug("Created ConfigMap: {}", configMapName);
    }

    // ========================================================================
    // ROLLBACK
    // ========================================================================

    public void rollback(Tenant tenant) {
        String namespace = resolveNamespace(tenant);
        String slug = tenant.getSlug();

        log.info("Rolling back load balancer for tenant: {}", tenant.getId());

        try {
            // Delete VirtualService
            kubernetesClient.genericKubernetesResources(VIRTUAL_SERVICE_CTX)
                    .inNamespace(namespace)
                    .withName("vs-" + slug)
                    .delete();

            // Delete RequestAuthentication
            kubernetesClient.genericKubernetesResources(REQUEST_AUTH_CTX)
                    .inNamespace(namespace)
                    .withName("ra-" + slug)
                    .delete();

            // Delete AuthorizationPolicy
            kubernetesClient.genericKubernetesResources(AUTH_POLICY_CTX)
                    .inNamespace(namespace)
                    .withName("ap-" + slug)
                    .delete();

            // Delete ConfigMaps by label
            kubernetesClient.configMaps()
                    .inNamespace(namespace)
                    .withLabel("dalai.llama/tenant-id", tenant.getId().toString())
                    .delete();

            log.info("Rollback completed for tenant {}", tenant.getId());

        } catch (Exception e) {
            log.warn("Error during rollback for tenant {}: {}", tenant.getId(), e.getMessage());
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private String resolveNamespace(Tenant tenant) {
        //return tenant.getNamespace() != null
        //        ? tenant.getNamespace()
        //        : "dalai-" + tenant.getSlug();
        return "dalai-" + tenant.getSlug();
    }

    private Map<String, String> buildLabels(Tenant tenant) {
        return Map.of(
                "app.kubernetes.io/managed-by", "dalai-provisioner",
                "dalai.llama/tenant", tenant.getSlug(),
                "dalai.llama/tenant-id", tenant.getId().toString()
        );
    }

    private Map<String, Object> prefixMatch(String prefix) {
        return Map.of("uri", Map.of("prefix", prefix));
    }

    private Map<String, Object> exactMatch(String exact) {
        return Map.of("uri", Map.of("exact", exact));
    }

    private Map<String, Object> destination(String service, String namespace, int port) {
        return Map.of(
                "destination", Map.of(
                        "host", service + "." + namespace + ".svc.cluster.local",
                        "port", Map.of("number", port)
                ),
                "weight", 100
        );
    }

    private Map<String, Object> createRoute(String name, List<Map<String, Object>> matches,
                                            Map<String, Object> dest, Map<String, Object> cors) {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("name", name);
        route.put("match", matches);
        route.put("route", List.of(dest));
        if (cors != null) {
            route.put("corsPolicy", cors);
        }
        return route;
    }

    private Map<String, Object> createWebSocketRoute(String name, String pathPrefix, Map<String, Object> dest) {
        return Map.of(
                "name", name,
                "match", List.of(Map.of(
                        "uri", Map.of("prefix", pathPrefix),
                        "headers", Map.of("upgrade", Map.of("exact", "websocket"))
                )),
                "route", List.of(dest),
                "timeout", "3600s"
        );
    }

    private Map<String, Object> createHostBasedRoute(String name, String host, Map<String, Object> dest) {
        return Map.of(
                "name", name,
                "match", List.of(Map.of(
                        "headers", Map.of("host", Map.of("exact", host)),
                        "uri", Map.of("prefix", "/")
                )),
                "route", List.of(dest)
        );
    }

    private void applyResource(CustomResourceDefinitionContext ctx,
                               GenericKubernetesResource resource,
                               String namespace) {
        kubernetesClient.genericKubernetesResources(ctx)
                .inNamespace(namespace)
                .resource(resource)
                .serverSideApply();
    }
}

