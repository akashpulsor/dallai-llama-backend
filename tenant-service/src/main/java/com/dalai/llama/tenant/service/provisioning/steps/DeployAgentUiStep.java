package com.dalai.llama.tenant.service.provisioning.steps;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.service.provisioning.ProvisioningStep;
import com.dalai.llama.tenant.service.provisioning.telecom.k8s.AgentUiK8sDeployer;
import com.dalai.llama.tenant.service.provisioning.telecom.model.TelecomStackConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kubernetes deployer for Agent UI (React Dashboard).
 *
 * Deploys:
 * - ConfigMap with runtime configuration (tenant-specific settings)
 * - Nginx deployment serving React SPA
 * - ClusterIP Service
 * - Ingress with TLS for custom domain ({tenant}.{baseDomain})
 * - Optional: Istio VirtualService for advanced routing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeployAgentUiStep implements ProvisioningStep {

    private final AgentUiK8sDeployer agentUiDeployer;
    private final TenantRepository tenantRepository;  // ADD THIS

    @Value("${dalaillama.domain:dalaillama.in}")
    private String baseDomain;

    @Override
    public String name() {
        return "DEPLOY_AGENT_UI";
    }

    @Override
    public void execute(Tenant tenant, String productCode) {
        //String namespace = tenant.getNamespace();
        TelecomStackConfig config = buildStackConfig(tenant);
        //agentUiDeployer.deployAgentUi(namespace, tenant, config);

        // Update dashboard URL
        //tenant.setDashboardUrl("https://" + tenant.getSlug() + "." + baseDomain);
        tenantRepository.save(tenant);
    }

    private TelecomStackConfig buildStackConfig(Tenant tenant) {
        return TelecomStackConfig.builder()
                .tenantId(tenant.getId().toString())
                .tenantSlug(tenant.getSlug())
                //.namespace(tenant.getNamespace())
                .realm(tenant.getSlug() + "." + baseDomain)
                .enableRecording(true)
                //.enableAiTranscription(tenant.isAiTranscriptionEnabled())
                //.enableAiRouting(tenant.isAiRoutingEnabled())
                .build();
    }
}