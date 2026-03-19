package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reconciles Traefik IngressRoutes from tenant_apps table (source of truth).
 *
 * Mirrors IstioHostReconciler — same DB query, same tenant-map ConfigMap,
 * but creates Traefik IngressRoute CRDs instead of Istio VirtualService/Gateway.
 *
 * Per tenant, creates one IngressRoute per app subdomain:
 *   {slug}-agent        → agent-{slug}.dalaillama.in
 *   {slug}-supervisor   → supervisor-{slug}.dalaillama.in
 *   {slug}-admin        → admin-{slug}.dalaillama.in
 *
 * Each IngressRoute has path-based routing:
 *   /api/* , /ws  → pbx-core:8080   (priority route, API + STOMP WebSocket)
 *   /*            → {app}-ui:80      (default route, React frontend)
 *
 * Labels: managed-by=dalaillama, tenant={slug}
 *   → On deprovision, delete by label selector: tenant={slug}
 *
 * Runs: on startup + every 60s + on-demand after provisioning.
 *
 * Switch to Istio later: set dalaillama.ingress.type=istio in application.yml
 * and this reconciler becomes no-op (IstioHostReconciler takes over).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraefikHostReconciler {

    private final TenantAppRepository tenantAppRepository;
    private final KubernetesClient kubeClient;
    private final ObjectMapper objectMapper;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String domain;

    @Value("${dalaillama.shared-namespace:telecom}")
    private String sharedNamespace;

    @Value("${dalaillama.ingress.type:traefik}")
    private String ingressType;

    @Value("${dalaillama.tls.cert-resolver:letsencrypt}")
    private String certResolver;

    private static final CustomResourceDefinitionContext INGRESS_ROUTE_CRD =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("traefik.io")
                    .withVersion("v1alpha1")
                    .withPlural("ingressroutes")
                    .withScope("Namespaced")
                    .build();

    private static final String LABEL_MANAGED_BY = "app.kubernetes.io/managed-by";
    private static final String LABEL_TENANT = "dalaillama.in/tenant";
    private static final String MANAGED_BY_VALUE = "dalaillama";

    // subdomain → K8s service name
    private static final Map<String, String> SUBDOMAIN_TO_SERVICE = Map.of(
            "agent", "agent-ui",
            "supervisor", "supervisor-ui",
            "admin", "admin-ui"
    );

    @PostConstruct
    public void onStartup() {
        if (!"traefik".equalsIgnoreCase(ingressType)) {
            log.info("Ingress type is '{}', TraefikHostReconciler disabled", ingressType);
            return;
        }
        log.info("TraefikHostReconciler: initial reconciliation");
        reconcile();
    }

    @Scheduled(fixedDelayString = "${dalaillama.reconcile-interval-ms:60000}")
    public void scheduledReconcile() {
        if (!"traefik".equalsIgnoreCase(ingressType)) return;
        reconcile();
    }

    /**
     * Main reconcile — called on startup, schedule, and after provisioning.
     */
    public void reconcile() {
        if (!"traefik".equalsIgnoreCase(ingressType)) return;

        try {
            // 1. Fetch all active shared tenant apps
            List<TenantApp> activeApps = tenantAppRepository
                    .findAllByDeploymentStatusAndDedicatedInfrastructure(
                            ProvisioningTaskStatus.COMPLETED, false);

            // 2. Build desired state: slug → set of subdomains from appPanels
            Map<String, Set<String>> desired = new LinkedHashMap<>();
            Map<String, String> slugToTenantId = new LinkedHashMap<>();

            for (TenantApp app : activeApps) {
                String slug = app.getTenant().getSlug();
                slugToTenantId.put(slug, app.getTenant().getId().toString());

                List<AppPanelInfo> panels = parseAppPanels(app.getAppPanels());
                Set<String> subdomains = panels.stream()
                        .filter(p -> p.enabled())
                        .map(AppPanelInfo::subdomain)
                        .filter(SUBDOMAIN_TO_SERVICE::containsKey)
                        .collect(Collectors.toSet());
                desired.merge(slug, subdomains, (a, b) -> { a.addAll(b); return a; });
            }

            // 3. Get current state: IngressRoutes managed by us
            Set<String> existingNames = getExistingIngressRouteNames();

            // 4. Build desired IngressRoute names
            Set<String> desiredNames = new HashSet<>();
            for (var entry : desired.entrySet()) {
                for (String subdomain : entry.getValue()) {
                    desiredNames.add(entry.getKey() + "-" + subdomain);
                }
            }

            // 5. Create missing
            for (var entry : desired.entrySet()) {
                String slug = entry.getKey();
                for (String subdomain : entry.getValue()) {
                    String name = slug + "-" + subdomain;
                    if (!existingNames.contains(name)) {
                        createIngressRoute(slug, subdomain);
                    }
                }
            }

            // 6. Delete stale (tenant deprovisioned or app removed)
            for (String existingName : existingNames) {
                if (!desiredNames.contains(existingName)) {
                    deleteIngressRoute(existingName);
                }
            }

            // 7. Reconcile tenant-map ConfigMap (same as Istio reconciler)
            reconcileTenantMap(slugToTenantId);

            log.debug("Traefik reconciled: {} tenants, {} IngressRoutes",
                    desired.size(), desiredNames.size());

        } catch (Exception e) {
            log.error("Traefik reconciliation failed", e);
        }
    }

    /**
     * Delete all IngressRoutes for a tenant — called during deprovision.
     */
    public void deleteForTenant(String slug) {
        try {
            kubeClient.genericKubernetesResources(INGRESS_ROUTE_CRD)
                    .inNamespace(sharedNamespace)
                    .withLabel(LABEL_MANAGED_BY, MANAGED_BY_VALUE)
                    .withLabel(LABEL_TENANT, slug)
                    .delete();
            log.info("Deleted Traefik IngressRoutes for tenant {}", slug);
        } catch (Exception e) {
            log.error("Failed to delete IngressRoutes for {}: {}", slug, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CREATE IngressRoute
    // ═══════════════════════════════════════════════════════════

    private void createIngressRoute(String slug, String subdomain) {
        String name = slug + "-" + subdomain;
        String host = subdomain + "-" + slug + "." + domain;
        String uiService = SUBDOMAIN_TO_SERVICE.get(subdomain);

        // Build the IngressRoute YAML as a Map → GenericKubernetesResource
        Map<String, Object> spec = buildIngressRouteSpec(host, uiService);

        try {
            Map<String, Object> resource = Map.of(
                    "apiVersion", "traefik.io/v1alpha1",
                    "kind", "IngressRoute",
                    "metadata", Map.of(
                            "name", name,
                            "namespace", sharedNamespace,
                            "labels", Map.of(
                                    LABEL_MANAGED_BY, MANAGED_BY_VALUE,
                                    LABEL_TENANT, slug,
                                    "dalaillama.in/subdomain", subdomain
                            )
                    ),
                    "spec", spec
            );

            GenericKubernetesResource gkr = objectMapper.convertValue(resource,
                    GenericKubernetesResource.class);

            kubeClient.genericKubernetesResources(INGRESS_ROUTE_CRD)
                    .inNamespace(sharedNamespace)
                    .resource(gkr)
                    .serverSideApply();

            log.info("Created IngressRoute: {} → {}", name, host);
        } catch (Exception e) {
            log.error("Failed to create IngressRoute {}: {}", name, e.getMessage());
        }
    }

    /**
     * Build IngressRoute spec with path-based routing:
     *   /api/*, /ws → pbx-core:8080  (higher priority)
     *   /*          → {ui-service}:80  (lower priority, React SPA)
     */
    private Map<String, Object> buildIngressRouteSpec(String host, String uiService) {
        // Route 1: PBX-Core API + STOMP WebSocket (higher priority via match specificity)
        Map<String, Object> apiRoute = Map.of(
                "match", "Host(`" + host + "`) && (PathPrefix(`/api`) || PathPrefix(`/ws`))",
                "kind", "Rule",
                "priority", 100,
                "services", List.of(Map.of(
                        "name", "pbx-core",
                        "port", 8080
                ))
        );

        // Route 2: Frontend UI (default, everything else)
        Map<String, Object> uiRoute = Map.of(
                "match", "Host(`" + host + "`)",
                "kind", "Rule",
                "priority", 50,
                "services", List.of(Map.of(
                        "name", uiService,
                        "port", 80
                ))
        );

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("entryPoints", List.of("websecure"));
        spec.put("routes", List.of(apiRoute, uiRoute));
        spec.put("tls", Map.of("certResolver", certResolver));

        return spec;
    }

    // ═══════════════════════════════════════════════════════════
    // DELETE IngressRoute
    // ═══════════════════════════════════════════════════════════

    private void deleteIngressRoute(String name) {
        try {
            kubeClient.genericKubernetesResources(INGRESS_ROUTE_CRD)
                    .inNamespace(sharedNamespace)
                    .withName(name)
                    .delete();
            log.info("Deleted stale IngressRoute: {}", name);
        } catch (Exception e) {
            log.warn("Failed to delete IngressRoute {}: {}", name, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // QUERY existing IngressRoutes
    // ═══════════════════════════════════════════════════════════

    private Set<String> getExistingIngressRouteNames() {
        try {
            return kubeClient.genericKubernetesResources(INGRESS_ROUTE_CRD)
                    .inNamespace(sharedNamespace)
                    .withLabel(LABEL_MANAGED_BY, MANAGED_BY_VALUE)
                    .list()
                    .getItems()
                    .stream()
                    .map(r -> r.getMetadata().getName())
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("Failed to list IngressRoutes: {}", e.getMessage());
            return Set.of();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // TENANT MAP ConfigMap (same as IstioHostReconciler)
    // ═══════════════════════════════════════════════════════════

    private void reconcileTenantMap(Map<String, String> slugToTenantId) {
        try {
            String desiredJson = objectMapper.writeValueAsString(new TreeMap<>(slugToTenantId));

            var existing = kubeClient.configMaps()
                    .inNamespace(sharedNamespace).withName("tenant-map").get();

            if (existing != null) {
                String current = existing.getData().getOrDefault("tenant-map.json", "{}");
                if (desiredJson.equals(current)) return;
            }

            var cm = new io.fabric8.kubernetes.api.model.ConfigMapBuilder()
                    .withNewMetadata()
                    .withName("tenant-map")
                    .withNamespace(sharedNamespace)
                    .addToLabels("managed-by", MANAGED_BY_VALUE)
                    .endMetadata()
                    .addToData("tenant-map.json", desiredJson)
                    .build();

            kubeClient.configMaps().inNamespace(sharedNamespace).createOrReplace(cm);
            log.info("Tenant map updated: {} tenants", slugToTenantId.size());
        } catch (Exception e) {
            log.error("Failed to reconcile tenant map", e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════

    private List<AppPanelInfo> parseAppPanels(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return Arrays.asList(objectMapper.readValue(json, AppPanelInfo[].class));
        } catch (Exception e) {
            log.error("Failed to parse app panels: {}", e.getMessage());
            return List.of();
        }
    }

    record AppPanelInfo(String appType, String displayName, String subdomain,
                        String url, String icon, int displayOrder,
                        String keycloakClientId, String frontendImage,
                        String requiredRoles,boolean enabled) {}
}