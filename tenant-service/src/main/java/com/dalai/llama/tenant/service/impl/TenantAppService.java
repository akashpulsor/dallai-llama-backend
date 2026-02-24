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


}