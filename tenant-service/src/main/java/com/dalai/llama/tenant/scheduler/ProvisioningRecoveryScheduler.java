package com.dalai.llama.tenant.scheduler;

import com.dalai.llama.tenant.domain.entity.ProvisioningTask;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.repository.ProvisioningTaskRepository;
import com.dalai.llama.tenant.service.ProvisioningOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Recovers orphaned provisioning tasks.
 *
 * If the pod crashes mid-provisioning, the task stays RUNNING forever.
 * This scheduler picks up RUNNING tasks older than 15 minutes
 * and re-queues them for retry (resumes from the stuck step).
 *
 * Also auto-retries FAILED tasks up to 3 times with 10-minute spacing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProvisioningRecoveryScheduler {

    private final ProvisioningTaskRepository taskRepository;
    private final ProvisioningOrchestrator provisioningOrchestrator;

    private static final int STALE_MINUTES = 15;
    private static final int MAX_AUTO_RETRIES = 3;

    @Scheduled(fixedDelayString = "${dalaillama.provisioning.recovery-interval-ms:300000}") // 5 min
    public void recoverStalledTasks() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(STALE_MINUTES);

        // 1. Recover RUNNING tasks that are stuck (pod crash)
        List<ProvisioningTask> stalled = taskRepository.findByStatusAndStartedAtBefore(
                ProvisioningTaskStatus.RUNNING, cutoff);

        for (ProvisioningTask task : stalled) {
            log.warn("Recovering stalled provisioning task {} (app={}, step={}, started={})",
                    task.getId(), task.getTenantAppId(), task.getCurrentStep(), task.getStartedAt());

            task.setStatus(ProvisioningTaskStatus.FAILED);
            task.setLastError("Recovered: task was RUNNING for >" + STALE_MINUTES + " minutes (possible pod crash)");
            task.setLastErrorAt(OffsetDateTime.now());
            taskRepository.save(task);

            // Re-trigger — orchestrator will resume from the stuck step
            try {
                provisioningOrchestrator.provision(task.getTenantAppId());
            } catch (Exception e) {
                log.error("Failed to re-trigger provisioning for app {}: {}", task.getTenantAppId(), e.getMessage());
            }
        }

        // 2. Auto-retry FAILED tasks (up to MAX_AUTO_RETRIES)
        List<ProvisioningTask> failed = taskRepository.findByStatusAndLastErrorAtBefore(
                ProvisioningTaskStatus.FAILED, OffsetDateTime.now().minusMinutes(10));

        for (ProvisioningTask task : failed) {
            if (task.getRetryCount() >= MAX_AUTO_RETRIES) {
                continue; // exhausted auto-retries, needs manual intervention
            }

            log.info("Auto-retrying failed provisioning task {} (app={}, step={}, retries={})",
                    task.getId(), task.getTenantAppId(), task.getCurrentStep(), task.getRetryCount());

            try {
                provisioningOrchestrator.provision(task.getTenantAppId());
            } catch (Exception e) {
                log.error("Failed to auto-retry provisioning for app {}: {}", task.getTenantAppId(), e.getMessage());
            }
        }

        if (!stalled.isEmpty() || !failed.isEmpty()) {
            log.info("Provisioning recovery: {} stalled recovered, {} failed retried",
                    stalled.size(), failed.stream().filter(t -> t.getRetryCount() < MAX_AUTO_RETRIES).count());
        }
    }
}
