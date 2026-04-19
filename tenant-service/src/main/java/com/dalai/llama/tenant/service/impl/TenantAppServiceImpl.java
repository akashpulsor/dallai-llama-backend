package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.domain.exception.ProvisioningException;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.dalai.llama.tenant.service.TenantAppService;
import com.dalai.llama.tenant.service.impl.ProvisioningOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantAppServiceImpl implements TenantAppService {

    private final TenantAppRepository tenantAppRepository;
    private final ProvisioningOrchestrator provisioningOrchestrator;

    @Override
    public Optional<TenantApp> getByDid(String did) {
        return tenantAppRepository.findByDidNumber(did);
    }

    @Override
    public Optional<TenantApp> getByTenantId(UUID tenantId) {
        return tenantAppRepository.findFirstByTenantId(tenantId);
    }

    @Override
    public void provisionApp(UUID tenantAppId) {
        TenantApp app = tenantAppRepository.findById(tenantAppId)
                .orElseThrow(() -> new ProvisioningException("TenantApp not found: " + tenantAppId));

        // Guard: only provision if PENDING or FAILED (retry)
        Set<ProvisioningTaskStatus> allowed = Set.of(
                ProvisioningTaskStatus.PENDING, ProvisioningTaskStatus.FAILED);
        if (!allowed.contains(app.getDeploymentStatus())) {
            throw new ProvisioningException(
                    "Cannot provision app in state " + app.getDeploymentStatus()
                            + ". Allowed: " + allowed);
        }

        log.info("Initiating provisioning for TenantApp={} tenant={}",
                tenantAppId, app.getTenant().getId());
        provisioningOrchestrator.provision(tenantAppId);
    }
}