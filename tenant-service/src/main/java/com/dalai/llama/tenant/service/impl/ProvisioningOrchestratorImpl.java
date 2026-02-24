package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.ProvisioningTask;
import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.DeploymentModel;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningStep;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import com.dalai.llama.tenant.domain.event.ProvisioningFailedEvent;
import com.dalai.llama.tenant.domain.event.ProvisioningStepCompletedEvent;
import com.dalai.llama.tenant.domain.exception.ProvisioningException;
import com.dalai.llama.tenant.domain.exception.TenantNotFoundException;
import com.dalai.llama.tenant.kafka.producer.TenantEventProducer;
import com.dalai.llama.tenant.repository.ProvisioningTaskRepository;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.service.*;
import com.dalai.llama.tenant.service.client.DidwwClient;

import com.dalai.llama.tenant.util.DistributedLock;
import com.dalai.llama.tenant.util.PasswordGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProvisioningOrchestratorImpl  {

    private final TenantRepository tenantRepository;
    private final ProvisioningTaskRepository taskRepository;
    private final TenantStateMachine stateMachine;
    private final ReadinessCheckService readinessCheckService;
    private final KeycloakRealmService keycloakRealmService;
    private final DidwwClient didwwClient;

    private final TenantEventProducer eventProducer;
    private final DistributedLock distributedLock;
    private final ObjectMapper objectMapper;

    @Value("${provisioning.max-retries:3}")
    private int maxRetries;

    @Value("${provisioning.retry-delay-seconds:10}")
    private int retryDelaySeconds;



    private final KubernetesClient kubernetesClient;

    // Add new @Value fields
    @Value("${provisioning.shared.telecom-namespace:platform-telecom}")
    private String sharedTelecomNamespace;

    @Value("${provisioning.shared.esl-password:ClueCon}")
    private String sharedEslPassword;

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @Value("${provisioning.telecom.ip-wait-timeout-minutes:10}")
    private int ipWaitTimeoutMinutes;
    private static final String LOCK_PREFIX = "tenant:provisioning:";


    public void setIdentityProvisioner(Tenant tenant) {
        String lockKey = LOCK_PREFIX + tenant.getId();
        String lockValue = UUID.randomUUID().toString();
        if (!distributedLock.acquire(lockKey, lockValue, Duration.ofMinutes(30))) {
             log.warn("Could not acquire lock for tenant {}, provisioning may already be in progress", tenant.getId());
              return;
        }
        try {

            ProvisioningTask provisioningTask =initializeProvisioningTask(tenant);
            executeStep(tenant, provisioningTask, ProvisioningStep.CREATE_KEYCLOAK_REALM);
            executeStep(tenant, provisioningTask, ProvisioningStep.CREATE_KEYCLOAK_ROLES);
            executeStep(tenant, provisioningTask, ProvisioningStep.CREATE_KEYCLOAK_CLIENT);
            executeStep(tenant, provisioningTask, ProvisioningStep.CREATE_KEYCLOAK_ADMIN);
        } finally {
            distributedLock.release(lockKey, lockValue);
        }
    }




    //@Async("taskExecutor")
    public void startProvisioning(UUID tenantId) {
        String lockKey = LOCK_PREFIX + tenantId;
        String lockValue = UUID.randomUUID().toString();

        //if (!distributedLock.acquire(lockKey, lockValue, Duration.ofMinutes(30))) {
       //     log.warn("Could not acquire lock for tenant {}, provisioning may already be in progress", tenantId);
      //      return;
        //}

        try {
            readinessCheckService.assertReady(tenantId);

        } finally {
            distributedLock.release(lockKey, lockValue);
        }
    }

    public ProvisioningTask initializeProvisioningTask(Tenant tenant) {
        ProvisioningTask task = new ProvisioningTask();
        task.setTenant(tenant);
        task.setStatus(ProvisioningTaskStatus.RUNNING);
        task.setMaxRetries(maxRetries);
        task.setStartedAt(OffsetDateTime.now());
        task.setCompletedSteps("[]");
        task.setStepResults("{}");
        return taskRepository.save(task);
    }

    @Async("taskExecutor")
    public void retryProvisioning(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));

        if (tenant.getStatus() != TenantStatus.ERROR) {
            throw new ProvisioningException("Tenant not in ERROR state, cannot retry");
        }

        String lockKey = LOCK_PREFIX + tenantId;
        String lockValue = UUID.randomUUID().toString();

        if (!distributedLock.acquire(lockKey, lockValue, Duration.ofMinutes(30))) {
            log.warn("Could not acquire lock for tenant {} retry", tenantId);
            return;
        }

        try {
            ProvisioningTask task = taskRepository
                    .findFirstByTenantIdAndStatusIn(tenantId, List.of(ProvisioningTaskStatus.FAILED))
                    .orElse(null);

            if (task != null && task.getRetryCount() < maxRetries) {
                task.setRetryCount(task.getRetryCount() + 1);
                task.setStatus(ProvisioningTaskStatus.RUNNING);
                taskRepository.save(task);

                stateMachine.transition(tenant, TenantStatus.PROVISIONING,
                        "RETRY", "Retry attempt " + task.getRetryCount());

                executeProvisioningFromStep(tenant, task, task.getCurrentStep());
            } else {
                log.warn("No retryable task found for tenant {} or max retries exceeded", tenantId);
            }
        } finally {
            distributedLock.release(lockKey, lockValue);
        }
    }

    // ========== Private Methods ==========

    @Transactional
    protected void executeProvisioning(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));

        ProvisioningTask task = new ProvisioningTask();
        task.setTenant(tenant);
        task.setStatus(ProvisioningTaskStatus.RUNNING);
        task.setMaxRetries(maxRetries);
        task.setStartedAt(OffsetDateTime.now());
        task.setCompletedSteps("[]");
        task.setStepResults("{}");
        task = taskRepository.save(task);

        stateMachine.transition(tenant, TenantStatus.PROVISIONING,
                "ORCHESTRATOR", "Starting provisioning");

        executeProvisioningFromStep(tenant, task, ProvisioningStep.CREATE_KEYCLOAK_REALM);
    }

    private void executeProvisioningFromStep(Tenant tenant, ProvisioningTask task, ProvisioningStep startStep) {
        ProvisioningStep[] steps = ProvisioningStep.values();
        int startIndex = startStep.ordinal();

        for (int i = startIndex; i < steps.length; i++) {
            ProvisioningStep step = steps[i];


            try {
                executeStep(tenant, task, step);
            } catch (Exception e) {
                handleStepFailure(tenant, task, step, e);
                return;
            }
        }

        completeProvisioning(tenant, task);
    }

    private void moveStep(Tenant tenant, ProvisioningTask task, ProvisioningStep step) {
        log.info("Executing step {} for tenant {}", step, tenant.getId());
        task.setCurrentStep(step);
        task.setCurrentStepStatus("RUNNING");
        task.setCurrentStepStartedAt(OffsetDateTime.now());
        taskRepository.save(task);

        TenantStatus substatus = mapStepToSubstatus(step);
        if (substatus != null && substatus != tenant.getStatus()) {
            stateMachine.transition(tenant, substatus, "STEP", step.getDescription());
        }

    }
    public void executeStep(Tenant tenant, ProvisioningTask task, ProvisioningStep step) {
        moveStep(tenant, task, step);

        Map<String, Object> result = switch (step) {
            case CREATE_KEYCLOAK_REALM -> executeKeycloakRealmStep(tenant);
            case CREATE_KEYCLOAK_ROLES -> executeKeycloakRolesStep(tenant);
            case CREATE_KEYCLOAK_CLIENT -> executeKeycloakClientStep(tenant);
            case CREATE_KEYCLOAK_ADMIN -> executeKeycloakAdminStep(tenant);



            case FINALIZE -> executeFinalizeStep(tenant);
        };

        recordStepCompletion(task, step, result);

        eventProducer.publish("provisioning.step.completed", tenant.getId().toString(),
                new ProvisioningStepCompletedEvent(tenant.getId(), step));

        log.info("Completed step {} for tenant {}", step, tenant.getId());
    }




    private void handleStepFailure(Tenant tenant, ProvisioningTask task, ProvisioningStep step, Exception e) {
        log.error("Step {} failed for tenant {}: {}", step, tenant.getId(), e.getMessage(), e);

        task.setStatus(ProvisioningTaskStatus.FAILED);
        task.setCurrentStepStatus("FAILED");
        task.setLastError(e.getMessage());
        task.setLastErrorAt(OffsetDateTime.now());
        taskRepository.save(task);

        stateMachine.transition(tenant, TenantStatus.ERROR,
                "STEP_FAILURE", "Failed at step " + step + ": " + e.getMessage());

        eventProducer.publish("provisioning.failed", tenant.getId().toString(),
                new ProvisioningFailedEvent(tenant.getId(), e.getMessage()));

        if (task.getRetryCount() < maxRetries) {
            log.info("Scheduling retry for tenant {} in {} seconds", tenant.getId(), retryDelaySeconds);
        } else {
            log.warn("Max retries exceeded for tenant {}, starting compensation", tenant.getId());
            startCompensation(tenant, task);
        }
    }

    private void startCompensation(Tenant tenant, ProvisioningTask task) {
        task.setStatus(ProvisioningTaskStatus.COMPENSATING);
        taskRepository.save(task);

        try {
            List<ProvisioningStep> completedSteps = getCompletedSteps(task);


            task.setStatus(ProvisioningTaskStatus.COMPENSATED);
            task.setCompletedAt(OffsetDateTime.now());
            taskRepository.save(task);
        } catch (Exception e) {
            log.error("Compensation failed for tenant {}: {}", tenant.getId(), e.getMessage());
        }
    }

    private void completeProvisioning(Tenant tenant, ProvisioningTask task) {
        log.info("Provisioning completed for tenant {}", tenant.getId());

        task.setStatus(ProvisioningTaskStatus.COMPLETED);
        task.setCompletedAt(OffsetDateTime.now());
        taskRepository.save(task);

        tenant.setActivatedAt(OffsetDateTime.now());
        stateMachine.transition(tenant, TenantStatus.ACTIVE,
                "ORCHESTRATOR", "Provisioning completed successfully");
    }



    private Map<String, Object> executeKeycloakRealmStep(Tenant tenant) {
        String realmName = "tenant-" + tenant.getSlug();
        keycloakRealmService.createRealm(realmName, tenant.getCompanyName());
        tenant.setKeycloakRealmName(realmName);
        tenantRepository.save(tenant);
        return Map.of("realmName", realmName);
    }

    private Map<String, Object> executeKeycloakRolesStep(Tenant tenant) {
        keycloakRealmService.createRoles(tenant.getKeycloakRealmName());
        return Map.of("roles", List.of("TENANT_ADMIN", "SUPERVISOR", "AGENT", "REPORTING_VIEWER", "QUALITY_ANALYST"));
    }

    private Map<String, Object> executeKeycloakClientStep(Tenant tenant) {
        String clientId = "dalaillama-" + tenant.getSlug();
        keycloakRealmService.createClient(tenant.getKeycloakRealmName(), clientId);

        //tenant.setKeycloakClientId(clientId);
        tenantRepository.save(tenant);
        return Map.of("clientId", clientId);
    }

    private Map<String, Object> executeKeycloakAdminStep(Tenant tenant) {
        String tempPassword = PasswordGenerator.generate(16);
        keycloakRealmService.createAdminUser(
                tenant,
                tenant.getKeycloakRealmName(),
                tenant.getPrimaryContactEmail(),
                tempPassword
        );
        tenant.setAdminUserEmail(tenant.getPrimaryContactEmail());
        tenantRepository.save(tenant);
        return Map.of("adminEmail", tenant.getPrimaryContactEmail());
    }



    private Map<String, Object> executeCreateLoadBalancerStep(Tenant tenant, String productCode,ProvisioningStep step) {
        return Map.of("step", step.name());
    }



    private Map<String, Object> executeDeployDashboardStep(Tenant tenant) {
        String dashboardUrl = "https://" + tenant.getSlug() + ".dalaillama.in";
        //tenant.setDashboardUrl(dashboardUrl);
        tenantRepository.save(tenant);
        return Map.of("dashboardUrl", dashboardUrl);
    }

    private Map<String, Object> executeCreateUsersStep(Tenant tenant) {
        return Map.of("adminEmail", tenant.getAdminUserEmail());
    }

    private Map<String, Object> executeHealthCheckStep(Tenant tenant) {
        return Map.of("healthy", true);
    }

    private Map<String, Object> executeFinalizeStep(Tenant tenant) {
        tenant.setActivatedAt(OffsetDateTime.now());
        tenantRepository.save(tenant);
        return Map.of("activatedAt", tenant.getActivatedAt().toString());
    }




    private TenantStatus mapStepToSubstatus(ProvisioningStep step) {
        return switch (step) {
            case CREATE_KEYCLOAK_REALM,
                 CREATE_KEYCLOAK_ROLES,
                 CREATE_KEYCLOAK_CLIENT,
                 CREATE_KEYCLOAK_ADMIN
                    -> TenantStatus.PROVISIONING_KEYCLOAK;

            default -> TenantStatus.PROVISIONING_INFRA; // or whatever fits
        };
    }

    private void recordStepCompletion(ProvisioningTask task, ProvisioningStep step, Map<String, Object> result) {
        try {
            List<String> completedSteps = objectMapper.readValue(
                    task.getCompletedSteps(), new TypeReference<List<String>>() {});
            completedSteps.add(step.name());
            task.setCompletedSteps(objectMapper.writeValueAsString(completedSteps));

            Map<String, Object> stepResults = objectMapper.readValue(
                    task.getStepResults(), new TypeReference<Map<String, Object>>() {});
            stepResults.put(step.name(), result);
            task.setStepResults(objectMapper.writeValueAsString(stepResults));

            task.setCurrentStepStatus("COMPLETED");
            taskRepository.save(task);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize step results", e);
        }
    }

    private List<ProvisioningStep> getCompletedSteps(ProvisioningTask task) {
        try {
            List<String> stepNames = objectMapper.readValue(
                    task.getCompletedSteps(), new TypeReference<List<String>>() {});
            return stepNames.stream()
                    .map(ProvisioningStep::valueOf)
                    .toList();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse completed steps", e);
            return Collections.emptyList();
        }
    }


}