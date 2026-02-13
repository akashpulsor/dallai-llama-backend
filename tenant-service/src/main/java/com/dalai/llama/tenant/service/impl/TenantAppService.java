package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.service.client.ProductServiceClient;
import com.dalai.llama.tenant.service.client.ProductServiceClient.ProductAppDto;
import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.AppType;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantAppService {

    private final TenantAppRepository tenantAppRepository;
    private final ProductServiceClient productServiceClient;

    /**
     * Get all enabled apps for a tenant
     */
    public List<TenantApp> getEnabledApps(UUID tenantId) {
        return tenantAppRepository.findByTenantIdAndEnabledTrueOrderByDisplayOrderAsc(tenantId);
    }

    /**
     * Get all apps for a tenant (including disabled)
     */
    public List<TenantApp> getAllApps(UUID tenantId) {
        return tenantAppRepository.findByTenantIdOrderByDisplayOrderAsc(tenantId);
    }

    /**
     * Get app by tenant and subdomain
     */
    public Optional<TenantApp> getAppBySubdomain(UUID tenantId, String subdomain) {
        return tenantAppRepository.findByTenantIdAndSubdomain(tenantId, subdomain);
    }

    /**
     * Get app by tenant and type
     */
    public Optional<TenantApp> getAppByType(UUID tenantId, AppType appType) {
        return tenantAppRepository.findByTenantIdAndAppType(tenantId, appType);
    }

    /**
     * Create tenant apps by fetching app config from Product Service.
     * Called during provisioning after Keycloak client is created.
     *
     * @param tenant      The tenant being provisioned
     * @param productCode The product code (e.g., "AI_CC", "CONV_IVR")
     * @return List of created tenant apps
     */
    @Transactional
    public List<TenantApp> createAppsFromProduct(Tenant tenant, String productCode) {
        log.info("Creating apps for tenant {} from product {}", tenant.getId(), productCode);

        // Validate prerequisite
      //  if (tenant.getKeycloakClientId() == null || tenant.getKeycloakClientId().isBlank()) {
      //      throw new IllegalStateException("Tenant's keycloakClientId must be set before creating apps. Run KeycloakClientStep first.");
       // }

        // Fetch apps from Product Service
        List<ProductAppDto> productApps = productServiceClient.getProductApps(productCode);

        if (productApps == null || productApps.isEmpty()) {
            log.warn("No apps found for product: {}", productCode);
            return List.of();
        }

        String slug = tenant.getSlug();
        //String primaryClientId = tenant.getKeycloakClientId();  // e.g., "dalaillama-acme"
        List<TenantApp> tenantApps = new ArrayList<>();

        for (ProductAppDto productApp : productApps) {
            AppType appType;
            try {
                appType = AppType.valueOf(productApp.getAppType());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown app type: {}, skipping", productApp.getAppType());
                continue;
            }

            // Skip if already exists
            if (tenantAppRepository.existsByTenantIdAndAppType(tenant.getId(), appType)) {
                log.debug("App {} already exists for tenant {}, skipping", appType, tenant.getId());
                continue;
            }

            // Derive Keycloak client ID
            String keycloakClientId = deriveKeycloakClientId("primaryClientId", productApp.getKeycloakClientSuffix());

            // Derive frontend service name
            String frontendService = slug + "-" + productApp.getSubdomain() + "-ui";

            TenantApp tenantApp = TenantApp.builder()
                    .tenant(tenant)
                    .appType(appType)
                    .subdomain(productApp.getSubdomain())
                    .displayName(productApp.getDisplayName())
                    .keycloakClientId(keycloakClientId)
                    .frontendService(frontendService)
                    .frontendImage(productApp.getFrontendImage())
                    .frontendPort(productApp.getFrontendPort() != null ? productApp.getFrontendPort() : 80)
                    .requiredRoles(productApp.getRequiredRoles())
                    .icon(productApp.getIcon())
                    .displayOrder(productApp.getDisplayOrder() != null ? productApp.getDisplayOrder() : 0)
                    .enabled(true)
                    .deploymentStatus(ProvisioningTaskStatus.PENDING)
                    .build();

            tenantApps.add(tenantApp);
            log.debug("Prepared tenant app: {} -> clientId: {}, service: {}",
                    appType, keycloakClientId, frontendService);
        }

        List<TenantApp> savedApps = tenantAppRepository.saveAll(tenantApps);
        log.info("Created {} apps for tenant {} from product {}", savedApps.size(), tenant.getId(), productCode);

        return savedApps;
    }

    /**
     * Derive Keycloak client ID from primary client and suffix
     */
    private String deriveKeycloakClientId(String primaryClientId, String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return primaryClientId;  // Use primary client (e.g., CONTACT_CENTER)
        }
        return primaryClientId + "-" + suffix;  // e.g., "dalaillama-acme-ivr"
    }

    /**
     * Collect all domains for a tenant's apps (for VirtualService hosts)
     */
    public List<String> collectAllDomains(Tenant tenant) {
        String slug = tenant.getSlug();
        String baseDomain = slug + ".dalaillama.in";

        List<String> domains = new ArrayList<>();
        domains.add(baseDomain);
        domains.add("api." + baseDomain);
        domains.add("ws." + baseDomain);

        List<TenantApp> apps = getEnabledApps(tenant.getId());
        for (TenantApp app : apps) {
            domains.add(app.getFullDomain(slug));
        }

        return domains;
    }

    /**
     * Get all Keycloak client IDs for enabled apps
     */
    public List<String> getEnabledClientIds(UUID tenantId) {
        return tenantAppRepository.findEnabledClientIdsByTenantId(tenantId);
    }

    /**
     * Update deployment status for an app
     */
    @Transactional
    public void updateDeploymentStatus(UUID appId, ProvisioningTaskStatus status) {
        tenantAppRepository.updateDeploymentStatus(appId, status);
        log.debug("Updated app {} deployment status to {}", appId, status);
    }

    /**
     * Delete all apps for a tenant (used during rollback/deprovisioning)
     */
    @Transactional
    public void deleteAllAppsForTenant(UUID tenantId) {
        tenantAppRepository.deleteByTenantId(tenantId);
        log.info("Deleted all apps for tenant {}", tenantId);
    }

    /**
     * Get additional Keycloak client IDs to create (excluding primary)
     * Used by KeycloakClientProvisioningStep
     */
    public List<String> getAdditionalClientIdsToCreate(String primaryClientId, String productCode) {
        List<ProductAppDto> productApps = productServiceClient.getProductApps(productCode);

        if (productApps == null) {
            return List.of();
        }

        return productApps.stream()
                .filter(pa -> pa.getKeycloakClientSuffix() != null && !pa.getKeycloakClientSuffix().isBlank())
                .map(pa -> primaryClientId + "-" + pa.getKeycloakClientSuffix())
                .distinct()
                .toList();
    }
}