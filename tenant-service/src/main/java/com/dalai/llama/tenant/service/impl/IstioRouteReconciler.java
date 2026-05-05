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
 * (e.g., admin-acme.dalaillama.in, agent-acme.dalaillama.in)
 * route to the correct frontend service in the shared namespace.
 *
 * DNS setup (prerequisite):
 *   - Wildcard A record:  *.dalaillama.in  →  server IP
 *   - This single record covers ALL tenant subdomains automatically.
 *   - No per-tenant DNS changes needed.
 *
 * Architecture:
 *   - One Gateway "central-gateway" in istio-system with wildcard cert (*.dalaillama.in)
 *   - One VirtualService PER UI service (e.g. tenant-routes-admin-ui, tenant-routes-agent-ui)
 *   - Each VS lists all tenant hosts that route to that service
 *   - Istio matches incoming Host header against VS hosts to select the right VS
 *   - Reconciler is idempotent: reads all active TenantApps, builds desired state,
 *     applies VirtualServices to match, cleans up orphans.
 *
 * Example (tenant slug "acme", product AI_CC):
 *   VirtualService "tenant-routes-admin-ui"       hosts: [admin-acme.dalaillama.in]
 *   VirtualService "tenant-routes-dashboard-ui"    hosts: [app-acme.dalaillama.in]
 *   VirtualService "tenant-routes-agent-ui"        hosts: [agent-acme.dalaillama.in]
 *   VirtualService "tenant-routes-supervisor-ui"   hosts: [supervisor-acme.dalaillama.in]
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
            List<TenantApp> activeApps = appRepository.findByDeploymentStatusWithTenant(ProvisioningTaskStatus.COMPLETED);
            activeApps.addAll(appRepository.findByDeploymentStatusWithTenant(ProvisioningTaskStatus.RUNNING));
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
        // Group hosts by their destination service (many hosts → same service)
        // e.g. admin-acme, admin-beta → both go to admin-ui
        Map<String, List<String>> serviceToHosts = new LinkedHashMap<>();
        Map<String, RouteTarget> serviceToTarget = new LinkedHashMap<>();

        for (Map.Entry<String, RouteTarget> entry : routes.entrySet()) {
            String host = entry.getKey();
            RouteTarget target = entry.getValue();
            String key = target.serviceName + "/" + target.namespace + "/" + target.port;
            serviceToHosts.computeIfAbsent(key, k -> new ArrayList<>()).add(host);
            serviceToTarget.putIfAbsent(key, target);
        }

        // Create one VirtualService per service group.
        // Each VS has its own hosts list — Istio routes by Host header match.
        // This avoids the single-VS problem where host matching doesn't work
        // because all routes share the same hosts list.
        for (Map.Entry<String, List<String>> entry : serviceToHosts.entrySet()) {
            String serviceKey = entry.getKey();
            List<String> hosts = entry.getValue();
            RouteTarget target = serviceToTarget.get(serviceKey);

            String vsName = virtualServiceName + "-" + target.serviceName;

            HTTPRouteDestination dest = new HTTPRouteDestinationBuilder()
                    .withNewDestination()
                    .withHost(target.serviceName + "." + target.namespace + ".svc.cluster.local")
                    .withNewPort()
                    .withNumber(target.port)
                    .endPort()
                    .endDestination()
                    .build();

            HTTPRoute httpRoute = new HTTPRouteBuilder()
                    .withRoute(dest)
                    .build();

            VirtualService vs = new VirtualServiceBuilder()
                    .withNewMetadata()
                    .withName(vsName)
                    .withNamespace(sharedNamespace)
                    .withLabels(Map.of(
                            "app.kubernetes.io/managed-by", "tenant-service",
                            "app.kubernetes.io/part-of", "dalaillama"))
                    .endMetadata()
                    .withNewSpec()
                    .withHosts(hosts)
                    .withGateways(gatewayNamespace + "/" + gatewayName)
                    .withHttp(httpRoute)
                    .endSpec()
                    .build();

            istioClient.v1beta1().virtualServices()
                    .inNamespace(sharedNamespace)
                    .resource(vs)
                    .serverSideApply();

            log.info("Applied VirtualService '{}' hosts={} → {}.{}:{}",
                    vsName, hosts, target.serviceName, target.namespace, target.port);
        }

        // Cleanup: remove VS for services that no longer have any hosts
        Set<String> activeServiceNames = serviceToTarget.values().stream()
                .map(RouteTarget::serviceName)
                .collect(Collectors.toSet());
        cleanupOrphanedVirtualServices(activeServiceNames);
    }

    private void cleanupOrphanedVirtualServices(Set<String> activeServiceNames) {
        try {
            List<VirtualService> existing = istioClient.v1beta1().virtualServices()
                    .inNamespace(sharedNamespace)
                    .withLabel("app.kubernetes.io/managed-by", "tenant-service")
                    .list().getItems();

            for (VirtualService vs : existing) {
                String vsName = vs.getMetadata().getName();
                // Check if this VS corresponds to an active service
                boolean isActive = activeServiceNames.stream()
                        .anyMatch(svc -> vsName.equals(virtualServiceName + "-" + svc));

                if (!isActive) {
                    istioClient.v1beta1().virtualServices()
                            .inNamespace(sharedNamespace)
                            .withName(vsName)
                            .delete();
                    log.info("Deleted orphaned VirtualService '{}'", vsName);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to cleanup orphaned VirtualServices: {}", e.getMessage());
        }
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