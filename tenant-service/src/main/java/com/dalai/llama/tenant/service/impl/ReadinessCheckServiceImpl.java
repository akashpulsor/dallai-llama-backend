package com.dalai.llama.tenant.service.impl;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import com.dalai.llama.tenant.domain.exception.ProvisioningException;
import com.dalai.llama.tenant.domain.exception.TenantNotFoundException;
import com.dalai.llama.tenant.dto.response.ReadinessCheckResponse;
import com.dalai.llama.tenant.repository.ProvisioningTaskRepository;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.service.ReadinessCheckService;
import com.dalai.llama.tenant.service.client.BillingServiceClient;
import com.dalai.llama.tenant.service.client.ProductServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReadinessCheckServiceImpl implements ReadinessCheckService {

    private final TenantRepository tenantRepository;
    private final ProvisioningTaskRepository provisioningTaskRepository;
    private final ProductServiceClient productServiceClient;
    private final BillingServiceClient billingServiceClient;

    @Value("${provisioning.min-wallet-balance:1000}")
    private int minWalletBalance;

    @Override
    @Transactional(readOnly = true)
    public ReadinessCheckResponse check(UUID tenantId) {
        List<String> failedChecks = new ArrayList<>();

        // 1. Tenant exists
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) {
            return new ReadinessCheckResponse(false, "Tenant not found");
        }

        // 2. Tenant not deleted
        if (tenant.getDeletedAt() != null) {
            return new ReadinessCheckResponse(false, "Tenant is deleted");
        }

        // 3. Plan assigned
        //if (tenant.getPlanId() == null) {
        //    failedChecks.add("No plan assigned");
        //}

        // 4. Deployment model set
        //if (tenant.getDeploymentModel() == null) {
        //    failedChecks.add("Deployment model not set");
        //}

        // 5. At least 1 DID purchased (call Product Service)
        try {
            boolean hasDid = productServiceClient.hasPurchasedDid(tenantId);
            if (!hasDid) {
                failedChecks.add("No DID purchased");
            }
        } catch (Exception e) {
            log.warn("Failed to check DID for tenant {}: {}", tenantId, e.getMessage());
            failedChecks.add("Unable to verify DID status");
        }

        // 6. Wallet exists and has minimum balance
        if (tenant.getWalletId() == null) {
            failedChecks.add("Wallet not created");
        } else {
            try {
                boolean hasSufficientBalance = billingServiceClient
                        .hasSufficientBalance(tenant.getWalletId(), minWalletBalance);
                if (!hasSufficientBalance) {
                    failedChecks.add("Insufficient wallet balance (min: ₹" + minWalletBalance + ")");
                }
            } catch (Exception e) {
                log.warn("Failed to check wallet balance for tenant {}: {}", tenantId, e.getMessage());
                failedChecks.add("Unable to verify wallet balance");
            }
        }

        // 7. Billing state is ACTIVE
        if (!"ACTIVE".equals(tenant.getBillingState())) {
            failedChecks.add("Billing state is not ACTIVE: " + tenant.getBillingState());
        }

        // 8. No pending/running provisioning task
        boolean hasActiveTask = provisioningTaskRepository.existsByTenantIdAndStatus(
                tenantId, ProvisioningTaskStatus.RUNNING) ||
                provisioningTaskRepository.existsByTenantIdAndStatus(
                        tenantId, ProvisioningTaskStatus.PENDING);
        if (hasActiveTask) {
            failedChecks.add("Provisioning already in progress");
        }

        // 9. Tenant in correct status for provisioning
        if (!isValidStatusForProvisioning(tenant.getStatus())) {
            failedChecks.add("Tenant status not ready: " + tenant.getStatus());
        }

        if (failedChecks.isEmpty()) {
            return new ReadinessCheckResponse(true, "All checks passed");
        } else {
            String message = String.join("; ", failedChecks);
            log.info("Readiness check failed for tenant {}: {}", tenantId, message);
            return new ReadinessCheckResponse(false, message);
        }
    }

    @Override
    public void assertReady(UUID tenantId) {
        ReadinessCheckResponse result = check(tenantId);
        if (!result.ready()) {
            throw new ProvisioningException("Tenant not ready for provisioning: " + result.message());
        }
    }

    private boolean isValidStatusForProvisioning(TenantStatus status) {
        return status == TenantStatus.READY_TO_PROVISION ||
                status == TenantStatus.BILLING_READY ||
                status == TenantStatus.ERROR; // Allow retry from error
    }
}