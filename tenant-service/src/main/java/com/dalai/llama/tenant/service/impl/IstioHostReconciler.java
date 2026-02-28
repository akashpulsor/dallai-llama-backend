package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.istio.api.networking.v1beta1.Gateway;
import io.fabric8.istio.api.networking.v1beta1.VirtualService;
import io.fabric8.istio.client.IstioClient;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reconciles Istio Gateway + VirtualService + tenant-map ConfigMap
 * from tenant_apps table (source of truth).
 *
 * Reads all ACTIVE TenantApps, parses their appPanels JSON,
 * builds desired hosts, compares with K8s state. No-op if in sync.
 *
 * URL pattern: {subdomain}-{tenantSlug}.dalaillama.in
 *   admin-acme.dalaillama.in  → admin-ui pod
 *   agent-acme.dalaillama.in  → agent-ui pod
 *
 * Runs: on startup + every 60s + on-demand after provisioning.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IstioHostReconciler {

    private final TenantAppRepository tenantAppRepository;
    private final IstioClient istioClient;
    private final KubernetesClient kubeClient;
    private final ObjectMapper objectMapper;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String domain;

    @Value("${dalaillama.shared-namespace:telecom}")
    private String sharedNamespace;

    private static final String GATEWAY_NAME = "tenant-ui-gateway";
    private static final String GATEWAY_SERVER_NAME = "https-tenant-ui";
    private static final String TENANT_MAP_CM = "tenant-map";

    // VS name → K8s service name (shared deployments in telecom namespace)
    private static final Map<String, String> UI_SERVICE_MAP = Map.of(
            "agent-ui-vs", "agent-ui",
            "supervisor-ui-vs", "supervisor-ui",
            "admin-ui-vs", "admin-ui"
    );

    // subdomain prefix → VS name
    private static final Map<String, String> SUBDOMAIN_TO_VS = Map.of(
            "agent", "agent-ui-vs",
            "supervisor", "supervisor-ui-vs",
            "admin", "admin-ui-vs"
    );

    @PostConstruct
    public void onStartup() {
        log.info("Reconciling Istio hosts from DB on startup");
        reconcile();
    }

    @Scheduled(fixedDelayString = "${dalaillama.reconcile-interval-ms:60000}")
    public void scheduledReconcile() {
        reconcile();
    }

    /**
     * Main reconcile — called on startup, schedule, and after provisioning.
     */
    public void reconcile() {
        try {
            // Fetch all active shared tenant apps
            List<TenantApp> activeApps = tenantAppRepository
                    .findAllByDeploymentStatusAndDedicatedInfrastructure(
                            ProvisioningTaskStatus.COMPLETED, false);

            // Build tenant data: slug → tenantId, slug → list of subdomains
            Map<String, String> slugToId = new LinkedHashMap<>();
            Map<String, Set<String>> slugToSubdomains = new LinkedHashMap<>();

            for (TenantApp app : activeApps) {
                String slug = app.getTenant().getSlug();
                String tenantId = app.getTenant().getId().toString();
                slugToId.put(slug, tenantId);

                List<AppPanelInfo> panels = parseAppPanels(app.getAppPanels());
                Set<String> subdomains = panels.stream()
                        .map(AppPanelInfo::subdomain)
                        .filter(SUBDOMAIN_TO_VS::containsKey)
                        .collect(Collectors.toSet());
                slugToSubdomains.merge(slug, subdomains, (a, b) -> {
                    a.addAll(b);
                    return a;
                });
            }

            boolean gwChanged = reconcileGateway(slugToSubdomains);
            boolean vsChanged = reconcileVirtualServices(slugToSubdomains);
            boolean cmChanged = reconcileTenantMap(slugToId);

            if (!gwChanged && !vsChanged && !cmChanged) {
                log.debug("No drift, {} active tenants", slugToId.size());
            }
        } catch (Exception e) {
            log.error("Reconciliation failed", e);
        }
    }

    // ── Tenant Map ConfigMap ────────────────────────────

    private boolean reconcileTenantMap(Map<String, String> slugToId) {
        try {
            String desiredJson = objectMapper.writeValueAsString(new TreeMap<>(slugToId));

            ConfigMap existing = kubeClient.configMaps()
                    .inNamespace(sharedNamespace).withName(TENANT_MAP_CM).get();

            if (existing != null) {
                String currentJson = existing.getData().getOrDefault("tenant-map.json", "{}");
                if (desiredJson.equals(currentJson)) return false;
            }

            ConfigMap cm = new ConfigMapBuilder()
                    .withNewMetadata()
                    .withName(TENANT_MAP_CM)
                    .withNamespace(sharedNamespace)
                    .addToLabels("managed-by", "telecom-ui")
                    .endMetadata()
                    .addToData("tenant-map.json", desiredJson)
                    .build();

            kubeClient.configMaps().inNamespace(sharedNamespace).createOrReplace(cm);
            log.info("Tenant map updated: {} tenants", slugToId.size());
            return true;
        } catch (Exception e) {
            log.error("Failed to reconcile tenant map", e);
            return false;
        }
    }

    // ── Gateway ─────────────────────────────────────────

    private boolean reconcileGateway(Map<String, Set<String>> slugToSubdomains) {
        Gateway gw = istioClient.v1beta1().gateways()
                .inNamespace(sharedNamespace).withName(GATEWAY_NAME).get();
        if (gw == null) {
            log.warn("Gateway {} not found in {}", GATEWAY_NAME, sharedNamespace);
            return false;
        }

        Set<String> desiredHosts = buildDesiredHosts(slugToSubdomains);

        return gw.getSpec().getServers().stream()
                .filter(s -> GATEWAY_SERVER_NAME.equals(s.getPort().getName()))
                .findFirst()
                .map(server -> {
                    Set<String> currentHosts = new TreeSet<>(server.getHosts());
                    Set<String> currentTenantHosts = currentHosts.stream()
                            .filter(this::isTenantHost)
                            .collect(Collectors.toCollection(TreeSet::new));
                    Set<String> nonTenantHosts = currentHosts.stream()
                            .filter(h -> !isTenantHost(h))
                            .collect(Collectors.toCollection(TreeSet::new));

                    if (currentTenantHosts.equals(desiredHosts)) return false;

                    Set<String> finalHosts = new LinkedHashSet<>(nonTenantHosts);
                    finalHosts.addAll(desiredHosts);
                    if (finalHosts.isEmpty()) finalHosts.add("admin-placeholder." + domain);

                    server.setHosts(new ArrayList<>(finalHosts));
                    istioClient.v1beta1().gateways()
                            .inNamespace(sharedNamespace).withName(GATEWAY_NAME).replace(gw);
                    log.info("Gateway reconciled: {} tenant hosts", desiredHosts.size());
                    return true;
                })
                .orElse(false);
    }

    // ── VirtualServices ─────────────────────────────────

    private boolean reconcileVirtualServices(Map<String, Set<String>> slugToSubdomains) {
        boolean anyChanged = false;

        for (var entry : SUBDOMAIN_TO_VS.entrySet()) {
            String subdomain = entry.getKey();
            String vsName = entry.getValue();

            VirtualService vs = istioClient.v1beta1().virtualServices()
                    .inNamespace(sharedNamespace).withName(vsName).get();
            if (vs == null) continue;

            // Only include slugs that have this subdomain in their appPanels
            Set<String> desiredHosts = slugToSubdomains.entrySet().stream()
                    .filter(e -> e.getValue().contains(subdomain))
                    .map(e -> subdomain + "-" + e.getKey() + "." + domain)
                    .collect(Collectors.toCollection(TreeSet::new));

            if (desiredHosts.isEmpty()) {
                desiredHosts.add(subdomain + "-placeholder." + domain);
            }

            Set<String> currentHosts = new TreeSet<>(vs.getSpec().getHosts());
            if (currentHosts.equals(desiredHosts)) continue;

            vs.getSpec().setHosts(new ArrayList<>(desiredHosts));
            istioClient.v1beta1().virtualServices()
                    .inNamespace(sharedNamespace).withName(vsName).replace(vs);
            log.info("VS {} reconciled: {} hosts", vsName, desiredHosts.size());
            anyChanged = true;
        }
        return anyChanged;
    }

    // ── Helpers ──────────────────────────────────────────

    private Set<String> buildDesiredHosts(Map<String, Set<String>> slugToSubdomains) {
        Set<String> hosts = new TreeSet<>();
        for (var entry : slugToSubdomains.entrySet()) {
            String slug = entry.getKey();
            for (String subdomain : entry.getValue()) {
                if (SUBDOMAIN_TO_VS.containsKey(subdomain)) {
                    hosts.add(subdomain + "-" + slug + "." + domain);
                }
            }
        }
        return hosts;
    }

    private boolean isTenantHost(String host) {
        if (!host.endsWith("." + domain)) return false;
        String prefix = host.replace("." + domain, "");
        if (prefix.endsWith("-placeholder")) return false;
        return SUBDOMAIN_TO_VS.keySet().stream().anyMatch(sub -> prefix.startsWith(sub + "-"));
    }

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
                        String requiredRoles) {}
}