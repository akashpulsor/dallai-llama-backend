package com.dalai.llama.tenant.scheduler;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.ProvisioningTaskStatus;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import com.dalai.llama.tenant.repository.ProvisioningTaskRepository;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.service.KeycloakRealmService;
import com.dalai.llama.tenant.service.TenantStateMachine;
import com.dalai.llama.tenant.service.client.BillingServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Cleanup job for expired/abandoned tenants.
 *
 * Runs hourly, deletes tenants where:
 * - status = CREATED (never subscribed)
 * - expiresAt < now (24-hour window passed)
 * - wallet balance = 0
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class TenantCleanupScheduler {

    private final TenantRepository tenantRepository;
    private final BillingServiceClient billingServiceClient;
    private final TenantStateMachine stateMachine;
    private final ProvisioningTaskRepository taskRepository;
    private final KeycloakRealmService keycloakRealmService;
    /**
     * Run every hour
     */
//    @Scheduled(cron = "0 0 * * * ?") // Every hour at :00
    @Transactional
    public void cleanupExpiredTenants() {
        log.info("Starting expired tenant cleanup");

        List<Tenant> expiredTenants = tenantRepository.findByExpiresAtBefore(OffsetDateTime.now());

        int deleted = 0;
        int skipped = 0;

        for (Tenant tenant : expiredTenants) {
            try {
                if (canDelete(tenant)) {
                    deleteWallet(tenant);
                    deleteIdentity(tenant);
                    markDeleted(tenant);
                    deleted++;
                } else {
                    // Has funds, extend expiry
                    extendExpiry(tenant);
                    skipped++;
                }
            } catch (Exception e) {
                log.error("Failed to cleanup tenant {}: {}", tenant.getId(), e.getMessage());
            }
        }

        log.info("Tenant cleanup completed. Deleted: {}, Skipped (has funds): {}", deleted, skipped);
    }

    private boolean canDelete(Tenant tenant) {
        // Check wallet balance
        try {
            BigDecimal balance = billingServiceClient.getBalance(tenant.getId());
            if (balance != null && balance.compareTo(BigDecimal.ZERO) > 0) {
                log.info("Tenant {} has balance ₹{}, cannot delete", tenant.getId(), balance);
                return false;
            }
        } catch (Exception e) {
            log.warn("Could not check balance for tenant {}: {}", tenant.getId(), e.getMessage());
        }

        return true;
    }

    private void deleteWallet(Tenant tenant) {
        try {
            billingServiceClient.deleteWallet(tenant.getId());
            log.info("Wallet deleted for tenant {}", tenant.getSlug());
        } catch (Exception e) {
            log.warn("Could not delete wallet for tenant {}: {}", tenant.getId(), e.getMessage());
        }
    }

    private void deleteIdentity(Tenant tenant) {
        try {
            keycloakRealmService.deleteTenant(tenant.getId());
            log.info("Keycloak realm deleted for tenant {}", tenant.getSlug());
        } catch (Exception e) {
            log.warn("Could not delete Keycloak realm for tenant {}: {}", tenant.getId(), e.getMessage());
        }
    }

    private void extendExpiry(Tenant tenant) {
        // User has added funds but not subscribed yet
        // Extend by another 24 hours
        tenant.setExpiresAt(OffsetDateTime.now().plusDays(1));
        tenant.setUpdatedAt(OffsetDateTime.now());
        tenantRepository.save(tenant);

        log.info("Extended expiry for tenant {} (has funds)", tenant.getSlug());
    }

    private void markDeleted(Tenant tenant) {
        tenant.setExpiresAt(OffsetDateTime.now());
        tenant.setUpdatedAt(OffsetDateTime.now());
        tenant.setStatus(TenantStatus.DELETED);
        tenantRepository.save(tenant);

        // Cancel any pending/running provisioning tasks so recovery scheduler doesn't pick them up
        int cancelled = taskRepository.cancelAllForTenant(
                tenant.getId(),
                List.of(ProvisioningTaskStatus.RUNNING, ProvisioningTaskStatus.FAILED, ProvisioningTaskStatus.PENDING),
                "Tenant deleted by cleanup scheduler",
                OffsetDateTime.now()
        );

        log.info("Marked tenant {} as DELETED, cancelled {} pending provisioning tasks",
                tenant.getSlug(), cancelled);
    }
}