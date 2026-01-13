package com.dalai.llama.tenant.service.provisioning.compensation;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningStep;
import com.dalai.llama.tenant.service.KeycloakRealmService;
import com.dalai.llama.tenant.service.impl.KubernetesProvisioningServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompensationExecutor {

    private final KeycloakRealmService keycloakRealmService;
    private final KubernetesProvisioningServiceImpl kubernetesService;

    /**
     * Execute compensation in reverse order for completed steps.
     * Only compensatable steps are rolled back.
     */
    public void compensate(Tenant tenant, List<ProvisioningStep> completedSteps) {
        log.info("Starting compensation for tenant {} with {} completed steps",
                tenant.getId(), completedSteps.size());

        // Reverse the order for compensation
        List<ProvisioningStep> reversed = new ArrayList<>(completedSteps);
        Collections.reverse(reversed);

        List<String> failedCompensations = new ArrayList<>();

        for (ProvisioningStep step : reversed) {
            if (!step.isCompensatable()) {
                log.debug("Skipping non-compensatable step: {}", step);
                continue;
            }

            try {
                compensateStep(tenant, step);
                log.info("Compensated step {} for tenant {}", step, tenant.getId());
            } catch (Exception e) {
                log.error("Failed to compensate step {} for tenant {}: {}",
                        step, tenant.getId(), e.getMessage());
                failedCompensations.add(step.name() + ": " + e.getMessage());
                // Continue with other compensations
            }
        }

        if (!failedCompensations.isEmpty()) {
            log.warn("Some compensations failed for tenant {}: {}",
                    tenant.getId(), failedCompensations);
        } else {
            log.info("Compensation completed successfully for tenant {}", tenant.getId());
        }
    }

    private void compensateStep(Tenant tenant, ProvisioningStep step) {
        switch (step) {
            // Keycloak steps - delete realm undoes all Keycloak operations
            case CREATE_KEYCLOAK_REALM -> {
                if (tenant.getKeycloakRealmName() != null) {
                    keycloakRealmService.deleteRealm(tenant.getKeycloakRealmName());
                }
            }
            // Other Keycloak steps are covered by realm deletion
            case CREATE_KEYCLOAK_ROLES, CREATE_KEYCLOAK_CLIENT, CREATE_KEYCLOAK_ADMIN -> {
                log.debug("Keycloak step {} covered by realm deletion", step);
            }

            // Kubernetes namespace - deleting namespace removes all resources
            case CREATE_NAMESPACE -> {
                if (tenant.getNamespace() != null) {
                    kubernetesService.deleteNamespace(tenant.getNamespace());
                }
            }

            // Infrastructure steps - covered by namespace deletion
            case DEPLOY_INFRA_KAFKA, DEPLOY_INFRA_REDIS, DEPLOY_INFRA_POSTGRES, DEPLOY_INFRA_MYSQL,
                 CREATE_KAFKA_TOPICS -> {
                log.debug("Infrastructure cleanup handled by namespace deletion");
            }

            // Telecom steps - covered by namespace deletion
            case DEPLOY_KAMAILIO, DEPLOY_RTPENGINE, DEPLOY_COTURN, DEPLOY_WEBRTC_GW, DEPLOY_ASTERISK -> {
                log.debug("Telecom cleanup handled by namespace deletion");
            }

            // Service steps - covered by namespace deletion
            case DEPLOY_AI_SERVICE, DEPLOY_AGENT_SERVICE, DEPLOY_CALL_CONTROL -> {
                log.debug("Service cleanup handled by namespace deletion");
            }

            // LoadBalancer - covered by namespace deletion
            case CREATE_SIP_LOADBALANCER, CREATE_WEBRTC_INGRESS -> {
                log.debug("LoadBalancer cleanup handled by namespace deletion");
            }

            // DIDWW configuration - delete trunk
            case CONFIGURE_DIDWW_TRUNK, CONFIGURE_DIDWW_DIDS -> {
                if (tenant.getDidwwTrunkId() != null) {
                    deleteDidwwTrunk(tenant);
                }
            }

            // Dashboard - covered by namespace deletion
            case DEPLOY_CC_DASHBOARD -> {
                log.debug("Dashboard cleanup handled by namespace deletion");
            }

            // Users - covered by Keycloak realm deletion
            case CREATE_TENANT_USERS -> {
                log.debug("Users cleanup handled by Keycloak realm deletion");
            }

            // Non-compensatable steps
            case WAIT_EXTERNAL_IP, HEALTH_CHECK, FINALIZE -> {
                log.debug("Step {} is not compensatable", step);
            }
        }
    }

    private void deleteDidwwTrunk(Tenant tenant) {
        log.info("Deleting DIDWW trunk for tenant {}: {}", tenant.getId(), tenant.getDidwwTrunkId());
        // TODO: Implement DIDWW trunk deletion via API
    }
}