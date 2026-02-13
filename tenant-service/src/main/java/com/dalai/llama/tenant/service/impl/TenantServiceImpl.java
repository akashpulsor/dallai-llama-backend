package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.*;
import com.dalai.llama.tenant.domain.entity.enums.DeploymentModel;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import com.dalai.llama.tenant.domain.event.TenantCreatedEvent;
import com.dalai.llama.tenant.domain.event.TenantDeletedEvent;
import com.dalai.llama.tenant.domain.exception.TenantAlreadyExistsException;
import com.dalai.llama.tenant.domain.exception.TenantNotFoundException;
import com.dalai.llama.tenant.dto.mapper.TenantMapper;
import com.dalai.llama.tenant.dto.request.CreateTenantRequest;
import com.dalai.llama.tenant.dto.request.UpdateTenantRequest;
import com.dalai.llama.tenant.dto.response.*;
import com.dalai.llama.tenant.kafka.producer.TenantEventProducer;
import com.dalai.llama.tenant.repository.*;
import com.dalai.llama.tenant.service.*;
import com.dalai.llama.tenant.service.client.BillingServiceClient;
import com.dalai.llama.tenant.service.client.ProductServiceClient;
import com.dalai.llama.tenant.util.SlugGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TenantServiceImpl implements TenantService {

    private final TenantRepository tenantRepository;
    private final ProvisioningTaskRepository provisioningTaskRepository;
    private final CompliancePolicyRepository compliancePolicyRepository;
    private final RecordingPolicyRepository recordingPolicyRepository;
    private final AgentCapacityRepository agentCapacityRepository;

    private final TenantMapper tenantMapper;
    private final TenantStateMachine stateMachine;
    private final ProvisioningOrchestrator orchestrator;
    private final ReadinessCheckService readinessCheckService;
    private final TenantEventProducer eventProducer;
    private final ProductServiceClient productServiceClient;
    private final BillingServiceClient billingServiceClient;
    private final ProvisioningOrchestrator provisioningOrchestrator;

    @Override
    public TenantResponse createTenant(CreateTenantRequest request, Jwt jwt) {
        String slug = SlugGenerator.generate(request.name());

        if (tenantRepository.existsBySlug(slug)) {
            throw new TenantAlreadyExistsException(slug);
        }

        // Create tenant
        Tenant tenant = tenantMapper.toEntity(request);
        tenant.setSlug(slug);
        tenant.setStatus(TenantStatus.CREATED);
        tenant.setAdminUserEmail(jwt.getSubject());
        tenant.setAdminUserId(jwt.getId());
        tenant.setCountry(request.country() != null ? request.country() : "IN");
        tenant.setTimezone(request.timezone() != null ? request.timezone() : "Asia/Kolkata");

        tenant = tenantRepository.save(tenant);

        // 4. Transition to PRODUCTS_CONFIGURED
        //stateMachine.transition(tenant, TenantStatus.PRODUCTS_CONFIGURED, "SYSTEM", "Internal compliance and recording policies initialized");

        // 2. Transition to PLAN_ASSIGNED
        //productServiceClient.assignDefaultPlan(tenant.getId(), request.productCode());
        //stateMachine.transition(tenant, TenantStatus.PLAN_ASSIGNED, "SYSTEM", "Default plan assigned: " + request.productCode());
        // 3. Configure Internal Policies (Compliance, Recording, Capacity)
        //createDefaultPolicies(tenant);
        //billingServiceClient.createWallet(tenant.getId());
        // Publish event
        // 5. START TECHNICAL PROVISIONING (Keycloak)
        // We transition to PROVISIONING_KEYCLOAK and call the realm service
        //stateMachine.transition(tenant, TenantStatus.PROVISIONING_KEYCLOAK, "ORCHESTRATOR", "Starting Keycloak Realm Provisioning");

        try {
            // This sets up Realm, Roles, Client, and Admin User
            //provisioningOrchestrator.setIdentityProvisioner(tenant);
            //provisioningOrchestrator
            // 6. Hand over to Infrastructure Provisioning
            //stateMachine.transition(tenant, TenantStatus.READY_TO_PROVISION, "KEYCLOAK_SERVICE", "Keycloak setup verified");

        } catch (Exception e) {
            log.error("Keycloak provisioning failed for tenant {}: {}", tenant.getId(), e.getMessage());
            stateMachine.transition(tenant, TenantStatus.ERROR, "KEYCLOAK_SERVICE", "Keycloak setup failed: " + e.getMessage());
            // Handle error/rollback if necessary
        }
        eventProducer.publish("tenant.created", tenant.getId().toString(),
                new TenantCreatedEvent(tenant.getId(), tenant.getSlug()));

        log.info("Created tenant: {} ({})", tenant.getName(), tenant.getSlug());
        return tenantMapper.toResponse(tenant);
    }

    @Override
    public void restartProvisioning(UUID tenantId, String reason) {
        Tenant tenant = findTenantOrThrow(tenantId);

        // PM Logic: Only allow restart if NOT currently in a successful ACTIVE state,
        // or allow it from ACTIVE if we are performing a 're-deployment'.
        log.info("Initiating provisioning restart for tenant: {} due to: {}", tenantId, reason);

        // 1. Transition State
        stateMachine.transition(tenant, TenantStatus.PROVISIONING_RESTART, "ADMIN", reason);

        // 2. Trigger Orchestrator (Orchestrator should handle task cleanup)
        orchestrator.startProvisioning(tenantId);
    }

    @Override
    public void rejectKyc(UUID tenantId, String reason) {
        Tenant tenant = findTenantOrThrow(tenantId);

        stateMachine.transition(tenant, TenantStatus.KYC_REJECTED, "COMPLIANCE_OFFICER", reason);

        // PM Note: You might want to trigger an email notification here
        // informing the user why their documents were rejected.
        log.info("KYC Rejected for tenant: {}. Reason: {}", tenantId, reason);
    }

    @Override
    public void approveKyc(UUID tenantId) {
        Tenant tenant = findTenantOrThrow(tenantId);

        stateMachine.transition(tenant, TenantStatus.KYC_APPROVED, "COMPLIANCE_OFFICER", "KYC documents verified successfully");

        // After approval, check if we can move closer to provisioning
        checkAndTransitionToReadyToProvision(tenant);
        log.info("KYC Approved for tenant: {}", tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantResponse> listTenants() {
        return tenantRepository.findAll().stream()
                .filter(t -> t.getDeletedAt() == null)
                .map(tenantMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TenantResponse getTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .map(tenantMapper::toResponse)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public TenantDetailResponse getTenantDetails(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .map(tenantMapper::toDetailResponse)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
    }

    @Override
    public TenantResponse updateTenant(UUID tenantId, UpdateTenantRequest request) {
        Tenant tenant = findTenantOrThrow(tenantId);
        tenantMapper.updateTenantFromRequest(request, tenant);
        tenant = tenantRepository.save(tenant);
        log.info("Updated tenant: {}", tenantId);
        return tenantMapper.toResponse(tenant);
    }

    @Override
    public void suspendTenant(UUID tenantId, String reason) {
        Tenant tenant = findTenantOrThrow(tenantId);
        stateMachine.transition(tenant, TenantStatus.SUSPENDED, "ADMIN", reason);
        log.info("Suspended tenant: {} - {}", tenantId, reason);
    }

    @Override
    public void activateTenant(UUID tenantId) {
        Tenant tenant = findTenantOrThrow(tenantId);
        stateMachine.transition(tenant, TenantStatus.ACTIVE, "ADMIN", "Reactivated by admin");
        log.info("Activated tenant: {}", tenantId);
    }

    @Override
    public void deleteTenant(UUID tenantId, String reason) {
        Tenant tenant = findTenantOrThrow(tenantId);
        stateMachine.transition(tenant, TenantStatus.DELETED, "ADMIN", reason);
        eventProducer.publish("tenant.deleted", tenantId.toString(),
                new TenantDeletedEvent(tenantId));
        log.info("Deleted tenant: {}", tenantId);
    }

    @Override
    public void triggerProvisioning(UUID tenantId) {
        ///readinessCheckService.assertReady(tenantId);
        orchestrator.startProvisioning(tenantId);
        log.info("Triggered provisioning for tenant: {}", tenantId);
    }

    @Override
    public void retryProvisioning(UUID tenantId) {
        orchestrator.retryProvisioning(tenantId);
        log.info("Retrying provisioning for tenant: {}", tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public ProvisioningStatusResponse getProvisioningStatus(UUID tenantId) {
        return provisioningTaskRepository
                .findFirstByTenantIdAndStatusIn(tenantId,
                        List.of(com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus.RUNNING,
                                com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus.PENDING,
                                com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus.FAILED))
                .map(tenantMapper::toProvisioningStatus)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public ReadinessCheckResponse checkReadiness(UUID tenantId) {
        return readinessCheckService.check(tenantId);
    }

    // ========== Internal Event Handlers ==========

    @Override
    public void onPlanAssigned(UUID tenantId, UUID planId, String planCode) {
        Tenant tenant = findTenantOrThrow(tenantId);
        //tenant.setPlanId(planId);
        //tenant.setPlanCode(planCode);
        //tenant.setPlanAssignedAt(OffsetDateTime.now());

        if (tenant.getStatus() == TenantStatus.CREATED) {
            stateMachine.transition(tenant, TenantStatus.PLAN_ASSIGNED,
                    "PRODUCT_SERVICE", "Plan assigned: " + planCode);
        }
        tenantRepository.save(tenant);
        log.info("Plan assigned to tenant {}: {}", tenantId, planCode);
    }

    @Override
    public void onDidPurchased(UUID tenantId) {
        Tenant tenant = findTenantOrThrow(tenantId);
        if (tenant.getStatus() == TenantStatus.PLAN_ASSIGNED) {
            stateMachine.transition(tenant, TenantStatus.PRODUCTS_CONFIGURED,
                    "PRODUCT_SERVICE", "DID purchased");
        }
        log.info("DID purchased for tenant: {}", tenantId);
    }

    @Override
    public void onWalletCreated(UUID tenantId, UUID walletId) {
        Tenant tenant = findTenantOrThrow(tenantId);
        tenant.setWalletId(walletId);
        tenantRepository.save(tenant);
        log.info("Wallet created for tenant {}: {}", tenantId, walletId);
    }

    @Override
    public void onWalletFunded(UUID tenantId) {
        Tenant tenant = findTenantOrThrow(tenantId);
        tenant.setBillingReadyAt(OffsetDateTime.now());

        if (tenant.getStatus() == TenantStatus.PRODUCTS_CONFIGURED) {
            stateMachine.transition(tenant, TenantStatus.BILLING_READY,
                    "BILLING_SERVICE", "Wallet funded");
            checkAndTransitionToReadyToProvision(tenant);
        }
        log.info("Wallet funded for tenant: {}", tenantId);
    }

    @Override
    public void onBillingStateChanged(UUID tenantId, String state) {
        Tenant tenant = findTenantOrThrow(tenantId);
        tenant.setBillingState(state);
        tenantRepository.save(tenant);

        // If billing is BLOCKED and tenant is ACTIVE, suspend them
        if ("BLOCKED".equals(state) && tenant.getStatus() == TenantStatus.ACTIVE) {
            stateMachine.transition(tenant, TenantStatus.SUSPENDED,
                    "BILLING_SERVICE", "Billing blocked");
        }
        log.info("Billing state changed for tenant {}: {}", tenantId, state);
    }

    @Override
    @Transactional(readOnly = true)
    public TenantProvisioningConfigResponse getProvisioningConfig(UUID tenantId) {
        Tenant t = findTenantOrThrow(tenantId);
        CompliancePolicy compliance = compliancePolicyRepository.findByTenantId(tenantId)
                .orElse(null);
/*
        return new TenantProvisioningConfigResponse(
                t.getId(),
                t.getSlug(),
                //t.getNamespace(),
                //t.getDeploymentModel() != null ? t.getDeploymentModel().name() : "SHARED",
                compliance != null ? compliance.getDataRegion().name() : "IN",
                //t.getKafkaBootstrap(),
                "registration-events-" + t.getSlug(),
                "call-events-" + t.getSlug(),
                "ai-results-" + t.getSlug(),
                "rtp-events-" + t.getSlug(),
                //t.getRedisUrl(),
                //t.getPostgresUrl(),
                //t.getMysqlUrl(),
                //t.getSipExternalIp(),
                //t.getSipUdpUrl(),
                //t.getSipTlsUrl(),
                //t.getTurnUrl(),
                //t.getWebsocketUrl(),
                //t.getRtpengineSock(),
                //t.getDidwwTrunkId(),
                //t.getDidwwSipConfigId(),
                //t.getDashboardUrl()
        );*/

        return null;
    }

    // ========== Private Helpers ==========

    private Tenant findTenantOrThrow(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
    }

    private void checkAndTransitionToReadyToProvision(Tenant tenant) {
        ReadinessCheckResponse readiness = readinessCheckService.check(tenant.getId());
        if (readiness.ready() && tenant.getStatus() == TenantStatus.BILLING_READY) {
            stateMachine.transition(tenant, TenantStatus.READY_TO_PROVISION,
                    "SYSTEM", "All prerequisites met");
        }
    }

    /**
     * Helper to encapsulate policy creation
     */
    private void createDefaultPolicies(Tenant tenant) {
        CompliancePolicy compliance = new CompliancePolicy();
        compliance.setTenant(tenant);
        compliancePolicyRepository.save(compliance);

        RecordingPolicy recording = new RecordingPolicy();
        recording.setTenant(tenant);
        recordingPolicyRepository.save(recording);

        AgentCapacity capacity = new AgentCapacity();
        capacity.setTenant(tenant);
        agentCapacityRepository.save(capacity);
    }
}