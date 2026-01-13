package com.dalai.llama.tenant.scheduler;


import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.service.ReadinessCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HealthCheckScheduler {

    private final TenantRepository tenantRepository;
    private final ReadinessCheckService readinessCheckService;

    @Scheduled(fixedDelayString = "${health-check.interval-seconds:60}000")
    public void runHealthChecks() {
        tenantRepository.findActiveTenants()
                .forEach(t -> readinessCheckService.check(t.getId()));
    }
}
