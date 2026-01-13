package com.dalai.llama.tenant.scheduler;


import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.service.ProvisioningOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProvisioningRetryScheduler {

    private final TenantRepository tenantRepository;
    private final ProvisioningOrchestrator orchestrator;

    @Scheduled(fixedDelay = 300_000) // every 5 minutes
    public void retryFailedProvisioning() {
        tenantRepository.findTenantsInErrorState()
                .forEach(t -> orchestrator.retryProvisioning(t.getId()));
    }
}
