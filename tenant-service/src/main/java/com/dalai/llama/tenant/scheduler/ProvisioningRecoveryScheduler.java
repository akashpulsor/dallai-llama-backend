package com.dalai.llama.tenant.scheduler;

import com.dalai.llama.tenant.domain.entity.ProvisioningTask;
import com.dalai.llama.tenant.domain.entity.TenantApp;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import com.dalai.llama.tenant.repository.ProvisioningTaskRepository;
import com.dalai.llama.tenant.repository.TenantAppRepository;
import com.dalai.llama.tenant.service.ProvisioningOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProvisioningRecoveryScheduler {

    private final ProvisioningTaskRepository taskRepository;
    private final TenantAppRepository tenantAppRepository;
    private final ProvisioningOrchestrator provisioningOrchestrator;

    private static final int STALE_MINUTES = 15;
    private static final int MAX_AUTO_RETRIES = 3;

    @Scheduled(fixedDelayString = "${dalaillama.provisioning.recovery-interval-ms:300000}")
    public void recoverStalledTasks() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(STALE_MINUTES);

        // 1. Recover RUNNING tasks that are stuck (pod crash)
        List<ProvisioningTask> stalled = taskRepository.findByStatusAndStartedAtBefore(
                ProvisioningTaskStatus.RUNNING, cutoff);

        int stalledRecovered = 0;
        int stalledSkipped = 0;
        for (ProvisioningTask task : stalled) {
            if (isTenantTerminallyGone(task.getTenantAppId())) {
                cancelTask(task, "Tenant is DELETED — recovery aborted");
                stalledSkipped++;
                continue;
            }

            log.warn("Recovering stalled provisioning task {} (app={}, step={}, started={})",
                    task.getId(), task.getTenantAppId(), task.getCurrentStep(), task.getStartedAt());

            task.setStatus(ProvisioningTaskStatus.FAILED);
            task.setLastError("Recovered: task was RUNNING for >" + STALE_MINUTES + " minutes (possible pod crash)");
            task.setLastErrorAt(OffsetDateTime.now());
            taskRepository.save(task);

            tenantAppRepository.findById(task.getTenantAppId()).ifPresent(app -> {
                app.setDeploymentStatus(ProvisioningTaskStatus.FAILED);
                tenantAppRepository.save(app);
            });

            try {
                provisioningOrchestrator.provision(task.getTenantAppId());
                stalledRecovered++;
            } catch (Exception e) {
                log.error("Failed to re-trigger provisioning for app {}: {}", task.getTenantAppId(), e.getMessage());
            }
        }

        // 2. Auto-retry FAILED tasks (up to MAX_AUTO_RETRIES)
        List<ProvisioningTask> failed = taskRepository.findByStatusAndLastErrorAtBefore(
                ProvisioningTaskStatus.FAILED, OffsetDateTime.now().minusMinutes(10));

        int failedRetried = 0;
        int failedSkipped = 0;
        for (ProvisioningTask task : failed) {
            if (task.getRetryCount() >= MAX_AUTO_RETRIES) {
                continue;
            }

            if (isTenantTerminallyGone(task.getTenantAppId())) {
                cancelTask(task, "Tenant is DELETED — auto-retry aborted");
                failedSkipped++;
                continue;
            }

            log.info("Auto-retrying failed provisioning task {} (app={}, step={}, retries={})",
                    task.getId(), task.getTenantAppId(), task.getCurrentStep(), task.getRetryCount());

            try {
                provisioningOrchestrator.provision(task.getTenantAppId());
                failedRetried++;
            } catch (Exception e) {
                log.error("Failed to auto-retry provisioning for app {}: {}", task.getTenantAppId(), e.getMessage());
            }
        }

        if (!stalled.isEmpty() || !failed.isEmpty()) {
            log.info("Provisioning recovery: stalled recovered={} skipped={}, failed retried={} skipped={}",
                    stalledRecovered, stalledSkipped, failedRetried, failedSkipped);
        }
    }

    /**
     * Returns true if the TenantApp's parent tenant is DELETED — meaning no recovery
     * should ever run, no matter what state the provisioning task is in.
     */
    private boolean isTenantTerminallyGone(java.util.UUID tenantAppId) {
        Optional<TenantApp> appOpt = tenantAppRepository.findById(tenantAppId);
        if (appOpt.isEmpty()) {
            return true; // app gone entirely — definitely don't retry
        }
        TenantStatus status = appOpt.get().getTenant().getStatus();
        return status == TenantStatus.DELETED;
    }

    private void cancelTask(ProvisioningTask task, String reason) {
        log.warn("Cancelling provisioning task {} for app {}: {}", task.getId(), task.getTenantAppId(), reason);
        task.setStatus(ProvisioningTaskStatus.CANCELLED);
        task.setLastError(reason);
        task.setLastErrorAt(OffsetDateTime.now());
        taskRepository.save(task);
    }
}