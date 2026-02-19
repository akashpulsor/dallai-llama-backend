package com.dalai.llama.tenant.scheduler;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.TenantStatus;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.service.KeycloakRealmService;
import com.dalai.llama.tenant.service.ProvisioningOrchestrator;
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

    private final KeycloakRealmService keycloakRealmService;
    /**
     * Run every hour
     */
    @Scheduled(cron = "0 0 * * * ?") // Every hour at :00
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
        log.info("Deleting expired tenant: {} ({})", tenant.getSlug(), tenant.getId());

        // 1. Delete wallet
        try {
            billingServiceClient.deleteWallet(tenant.getId());
            stateMachine.transition(tenant, TenantStatus.WALLET_DELETED, "ADMIN", "Tenant expired and deleted by scheduler");

        } catch (Exception e) {
            log.warn("Could not delete wallet for tenant {}: {}", tenant.getId(), e.getMessage());
        }

        // 2. Delete tenant record
        tenantRepository.delete(tenant);

        log.info("Deleted expired tenant: {}", tenant.getSlug());
    }

    private void deleteIdentity(Tenant tenant) {
        log.info("Deleting expired tenant: {} ({})", tenant.getSlug(), tenant.getId());

        // 1. Delete wallet
        try {

            keycloakRealmService.deleteTenant(tenant.getKeycloakRealmName());
            stateMachine.transition(tenant, TenantStatus.IDENTITY_DELETED, "ADMIN", "Tenant expired and Identity deleted by scheduler");

        } catch (Exception e) {
            log.warn("Could not delete wallet for tenant {}: {}", tenant.getId(), e.getMessage());
        }

        // 2. Delete tenant record
        tenantRepository.delete(tenant);

        log.info("Deleted expired tenant: {}", tenant.getSlug());
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
        // User has added funds but not subscribed yet
        // Extend by another 24 hours
        tenant.setExpiresAt(OffsetDateTime.now());
        tenant.setUpdatedAt(OffsetDateTime.now());
        tenant.setStatus(TenantStatus.DELETED);
        tenantRepository.save(tenant);

        log.info("Extended expiry for tenant {} (has funds)", tenant.getSlug());
    }
}