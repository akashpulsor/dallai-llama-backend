package com.dalai.llama.tenant.service.impl;


import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import io.fabric8.istio.api.networking.v1beta1.*;
import io.fabric8.istio.client.IstioClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Istio Route Reconciler
 *
 * Manages VirtualService entries so that tenant subdomain hosts
 * (e.g., admin-acme.dalaillama.in, app-acme.dalaillama.in)
 * route to the correct frontend service in the shared namespace.
 *
 * Architecture:
 *   - One Gateway "tenant-gateway" in the apps namespace with wildcard cert
 *   - One VirtualService "tenant-routes" with all tenant host entries
 *   - Reconciler is idempotent: reads all active TenantApps, builds desired state,
 *     patches the VirtualService to match.
 *
 * Called:
 *   1. Per-app during provisioning (reconcileForApp)
 *   2. Periodically to catch drift (reconcileAll, every 5 min)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IstioRouteReconciler {

    private final IstioClient istioClient;
    private final KubernetesClient kubernetesClient;
    private final TenantAppRepository appRepository;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @Value("${dalaillama.shared-namespace:apps}")
    private String sharedNamespace;

    @Value("${dalaillama.istio.gateway-name:central-gateway}")
    private String gatewayName;

    @Value("${dalaillama.istio.gateway-namespace:istio-system}")
    private String gatewayNamespace;

    @Value("${dalaillama.istio.vs-name:tenant-routes}")
    private String virtualServiceName;

    // ════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════

    /**
     * Adds routes for a single app. Calls full reconcile to stay consistent.
     */
    public void reconcileForApp(TenantApp app) {
        log.info("Reconciling Istio routes for app {} tenant {}", app.getId(), app.getTenant().getSlug());
        reconcileAll();
    }

    /**
     * Periodic full reconcile — builds desired VirtualService from DB truth.
     */
    @Scheduled(fixedDelayString = "${dalaillama.istio.reconcile-interval-ms:300000}")
    public void reconcileAll() {
        try {
            List<TenantApp> activeApps = appRepository.findByDeploymentStatus(ProvisioningTaskStatus.COMPLETED);
            // Also include apps currently being provisioned (so routes are ready when provisioning finishes)
            activeApps.addAll(appRepository.findByDeploymentStatus(ProvisioningTaskStatus.RUNNING));

            if (activeApps.isEmpty()) {
                log.debug("No active apps, skipping Istio reconciliation");
                return;
            }

            // Build desired hosts → service mapping
            Map<String, RouteTarget> desiredRoutes = buildDesiredRoutes(activeApps);

            // Ensure Gateway has all hosts
            ensureGatewayHosts(desiredRoutes.keySet());

            // Create/update VirtualService
            applyVirtualService(desiredRoutes);

            log.info("Istio reconciliation complete: {} routes for {} apps",
                    desiredRoutes.size(), activeApps.size());
        } catch (Exception e) {
            log.error("Istio reconciliation failed: {}", e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════
    // ROUTE BUILDING
    // ════════════════════════════════════════════════════════════

    private Map<String, RouteTarget> buildDesiredRoutes(List<TenantApp> apps) {
        Map<String, RouteTarget> routes = new LinkedHashMap<>();

        for (TenantApp app : apps) {
            String slug = app.getTenant().getSlug();
            String ns = app.getNamespace() != null ? app.getNamespace() : sharedNamespace;

            // Parse app panels to get all subdomain hosts
            // Each app panel has a URL like https://admin-acme.dalaillama.in
            // We also add the primary subdomain
            Set<String> hosts = extractHosts(app, slug);

            for (String host : hosts) {
                String serviceName = resolveServiceName(host, slug);
                int port = app.getFrontendPort() != null ? app.getFrontendPort() : 80;
                routes.put(host, new RouteTarget(serviceName, ns, port));
            }
        }
        return routes;
    }

    private Set<String> extractHosts(TenantApp app, String slug) {
        Set<String> hosts = new LinkedHashSet<>();

        // Primary dashboard URL
        if (app.getDashboardUrl() != null) {
            String host = app.getDashboardUrl()
                    .replace("https://", "")
                    .replace("http://", "")
                    .split("/")[0]; // strip path
            hosts.add(host);
        }

        // Standard subdomain patterns per product type
        hosts.add("admin-" + slug + "." + baseDomain);
        hosts.add("app-" + slug + "." + baseDomain);

        String productCode = app.getProductCode();
        if (productCode != null) {
            switch (productCode) {
                case "AI_CC" -> {
                    hosts.add("agent-" + slug + "." + baseDomain);
                    hosts.add("supervisor-" + slug + "." + baseDomain);
                }
                case "OUTBOUND_DIALER" -> hosts.add("dialer-" + slug + "." + baseDomain);
            }
        }

        return hosts;
    }

    private String resolveServiceName(String host, String slug) {
        // Map host prefix to K8s service name
        // admin-acme.dalaillama.in → admin-ui
        // agent-acme.dalaillama.in → agent-ui
        // supervisor-acme.dalaillama.in → supervisor-ui
        String prefix = host.replace("-" + slug + "." + baseDomain, "");
        return switch (prefix) {
            case "admin" -> "admin-ui";
            case "agent" -> "agent-ui";
            case "supervisor" -> "supervisor-ui";
            case "dialer" -> "dialer-ui";
            case "app" -> "dashboard-ui";
            default -> "dashboard-ui";
        };
    }

    // ════════════════════════════════════════════════════════════
    // ISTIO RESOURCE MANAGEMENT
    // ════════════════════════════════════════════════════════════

    private void ensureGatewayHosts(Set<String> hosts) {
        // The main Gateway should use a wildcard: *.dalaillama.in
        // If using cert-manager, the wildcard cert covers all subdomains.
        // No per-host updates needed for wildcard setup.
        // For non-wildcard, we'd add each host to the Gateway server.
        log.debug("Gateway hosts check: {} hosts (wildcard *.{} covers all)", hosts.size(), baseDomain);
    }

    private void applyVirtualService(Map<String, RouteTarget> routes) {
        // Build HTTP route list
        List<HTTPRoute> httpRoutes = new ArrayList<>();

        // Group by host for cleaner VS
        for (Map.Entry<String, RouteTarget> entry : routes.entrySet()) {
            String host = entry.getKey();
            RouteTarget target = entry.getValue();

            HTTPRouteBuilder routeBuilder = new HTTPRouteBuilder();

            // Match on host
            HTTPMatchRequestBuilder matchBuilder = new HTTPMatchRequestBuilder();
            StringMatchBuilder hostMatch = new StringMatchBuilder();
            hostMatch.withNewStringMatchExactType(host);

            // Note: host matching is done via the VirtualService hosts field, not match headers
            // Each route just needs destination

            HTTPRouteDestinationBuilder destBuilder = new HTTPRouteDestinationBuilder();
            destBuilder.withNewDestination()
                    .withHost(target.serviceName + "." + target.namespace + ".svc.cluster.local")
                    .withNewPort()
                    .withNumber(target.port)
                    .endPort()
                    .endDestination();

            routeBuilder.withRoute(destBuilder.build());
            httpRoutes.add(routeBuilder.build());
        }

        // Build VirtualService
        VirtualServiceBuilder vsBuilder = new VirtualServiceBuilder()
                .withNewMetadata()
                .withName(virtualServiceName)
                .withNamespace(sharedNamespace)
                .withLabels(Map.of(
                        "app.kubernetes.io/managed-by", "tenant-service",
                        "app.kubernetes.io/part-of", "dalaillama"))
                .endMetadata()
                .withNewSpec()
                .withHosts(new ArrayList<>(routes.keySet()))
                .withGateways(gatewayNamespace + "/" + gatewayName)
                .withHttp(httpRoutes)
                .endSpec();

        VirtualService vs = vsBuilder.build();

        // CreateOrReplace
        istioClient.v1beta1().virtualServices()
                .inNamespace(sharedNamespace)
                .resource(vs)
                .serverSideApply();

        log.info("Applied VirtualService '{}' with {} hosts in namespace '{}'",
                virtualServiceName, routes.size(), sharedNamespace);
    }

    // ════════════════════════════════════════════════════════════
    // CLEANUP
    // ════════════════════════════════════════════════════════════

    /**
     * Remove routes for a deprovisioned tenant.
     */
    public void removeRoutesForTenant(String tenantSlug) {
        log.info("Removing Istio routes for tenant {}", tenantSlug);
        // Full reconcile will exclude deleted/suspended apps
        reconcileAll();
    }

    // ════════════════════════════════════════════════════════════
    // INTERNAL
    // ════════════════════════════════════════════════════════════

    private record RouteTarget(String serviceName, String namespace, int port) {}
}